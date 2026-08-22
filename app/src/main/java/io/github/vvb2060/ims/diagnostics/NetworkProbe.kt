package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 蜂窝网络连通性探测。
 *
 * 刻意不依赖 `ip` / `ping` 外部命令：
 * ConnectivityManager 能直接给出地址、路由、DNS 与 VALIDATED 状态，
 * 且 socket 可以显式绑定到蜂窝 Network，比走默认路由的 ping 更能反映
 * 「蜂窝这条链路到底通不通」。
 *
 * 一条硬性原则：**读不到状态**与**确实没有网络**必须严格区分。
 * 把前者报成后者，会把排查引向完全错误的方向。
 */
object NetworkProbe {
    private const val TAG = "NetworkProbe"

    /** 蜂窝链路劣化时握手会明显变慢，超时太短会把慢误判成不通。 */
    private const val CONNECT_TIMEOUT_MILLIS = 5_000

    /**
     * 探测目标一律用中国大陆的公共服务：境外目标在国内网络下的可达性
     * 受跨境链路影响，无法反映本地蜂窝链路本身是否正常。
     *
     * 多目标多端口：单个主机被封或某端口被拦，都不足以判定「IP 不通」。
     * 任意一个成功即认为纯 IP 可达。
     */
    private val IP_TARGETS = listOf(
        "223.5.5.5" to "阿里 DNS",
        "119.29.29.29" to "腾讯 DNSPod",
        "180.76.76.76" to "百度 DNS",
    )

    /** 53 是这些 anycast 解析器最不容易被拦的端口，443 作为补充。 */
    private val IP_PORTS = listOf(53, 443)

    private val DNS_TARGETS = listOf("www.baidu.com", "www.qq.com")
    private const val DNS_PORT = 443

    /** 申请蜂窝网络的等待上限。 */
    private const val REQUEST_NETWORK_TIMEOUT_MILLIS = 8_000

    data class LinkSnapshot(
        /** true 表示链路状态根本没读出来（例如缺权限），与「确实没有蜂窝网络」是两回事。 */
        val unreadable: Boolean,
        val hasCellularNetwork: Boolean,
        val validated: Boolean?,
        val interfaceName: String?,
        val ipv4: List<String>,
        val ipv6: List<String>,
        val hasDefaultRouteV4: Boolean,
        val hasDefaultRouteV6: Boolean,
        val routes: List<String>,
        val dnsServers: List<String>,
        val capabilities: String?,
        val error: String?,
    )

    data class ProbeResult(
        val label: String,
        val target: String,
        val attempts: Int,
        val successes: Int,
        val lastError: String?,
        /** false 表示未绑定到蜂窝网络、实际走的是默认路由，结论不能当作蜂窝链路证据。 */
        val boundToCellular: Boolean,
        /** 每个目标各自的结果，用于分辨「全都不通」与「个别目标被拦」。 */
        val details: List<String>,
        /**
         * true 表示这次探测根本没能成立（例如 socket 绑定被拒），
         * 结果不构成任何网络结论 —— 那是工具自身的限制。
         */
        val unusable: Boolean = false,
    ) {
        val ok: Boolean get() = successes > 0
    }

    data class Result(
        val link: LinkSnapshot,
        val ipReachability: ProbeResult,
        val dnsResolution: ProbeResult,
        val verdict: String,
    )

    /**
     * 采集蜂窝链路状态并做有限次数的连通性测试。
     * 任何一步失败都记录进结果，不抛出，保证快照仍能生成。
     */
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        // 链路状态只需要 ACCESS_NETWORK_STATE，枚举即可读到。
        val lookup = findCellularNetwork(context)
        val link = collectLink(context, lookup)

        // 但要把 socket 绑上去，必须显式 requestNetwork 让系统授予使用权，
        // 否则 netd 会以 EPERM 拒绝绑定（枚举得到的 Network 不等于可用）。
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val requested = if (cm != null) {
                val (network, cb) = requestCellularNetwork(cm)
                callback = cb
                network
            } else {
                null
            }
            val cellular = requested ?: lookup.getOrNull()
            val ipProbe = probeIpReachability(cellular)
            val dnsProbe = probeDnsResolution(cellular)
            Result(link, ipProbe, dnsProbe, buildVerdict(link, ipProbe, dnsProbe))
        } finally {
            callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        }
    }

    /**
     * 显式申请一个可用的蜂窝网络。
     *
     * @return 拿到的网络（超时或不可用时为 null）与需要注销的 callback。
     */
    private suspend fun requestCellularNetwork(
        cm: ConnectivityManager,
    ): Pair<Network?, ConnectivityManager.NetworkCallback?> {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    private var settled = false
                    override fun onAvailable(network: Network) {
                        if (!settled) {
                            settled = true
                            cont.resume(network to this)
                        }
                    }

                    override fun onUnavailable() {
                        if (!settled) {
                            settled = true
                            cont.resume(null to this)
                        }
                    }
                }
                try {
                    cm.requestNetwork(request, cb, REQUEST_NETWORK_TIMEOUT_MILLIS)
                } catch (t: Throwable) {
                    Log.w(TAG, "requestNetwork failed", t)
                    cont.resume(null to null)
                }
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestCellularNetwork failed", t)
            null to null
        }
    }

    /** 绑定被拒是工具限制，不能当作网络故障。 */
    private fun isBindDenied(error: String?): Boolean {
        if (error == null) return false
        return error.contains("Binding socket to network") || error.contains("EPERM")
    }

    /**
     * @return success(network) 找到；success(null) 确实没有蜂窝网络；
     *         failure 表示读不出来（缺权限等），调用方必须与「没有」区别对待。
     */
    private fun findCellularNetwork(context: Context): kotlin.Result<Network?> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager unavailable")
        cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
    }.onFailure { Log.w(TAG, "findCellularNetwork failed", it) }

    private fun collectLink(context: Context, lookup: kotlin.Result<Network?>): LinkSnapshot {
        lookup.exceptionOrNull()?.let { return unreadableLink(describe(it)) }
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return unreadableLink("ConnectivityManager unavailable")
            val network = lookup.getOrNull() ?: return emptyLink(null)
            val caps = cm.getNetworkCapabilities(network)
            val lp: LinkProperties? = cm.getLinkProperties(network)
            val addresses = lp?.linkAddresses.orEmpty()
            val routes = lp?.routes.orEmpty()
            LinkSnapshot(
                unreadable = false,
                hasCellularNetwork = true,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                interfaceName = lp?.interfaceName,
                ipv4 = addresses.filter { it.address is Inet4Address }.map { it.toString() },
                ipv6 = addresses.filter { it.address is Inet6Address }.map { it.toString() },
                hasDefaultRouteV4 = routes.any { it.isDefaultRoute && it.destination.address is Inet4Address },
                hasDefaultRouteV6 = routes.any { it.isDefaultRoute && it.destination.address is Inet6Address },
                routes = routes.map { it.toString() },
                dnsServers = lp?.dnsServers.orEmpty().map { it.hostAddress ?: it.toString() },
                capabilities = caps?.toString(),
                error = null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "collectLink failed", t)
            unreadableLink(describe(t))
        }
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"

    private fun unreadableLink(error: String) = emptyLink(error).copy(unreadable = true)

    private fun emptyLink(error: String?) = LinkSnapshot(
        unreadable = false,
        hasCellularNetwork = false,
        validated = null,
        interfaceName = null,
        ipv4 = emptyList(),
        ipv6 = emptyList(),
        hasDefaultRouteV4 = false,
        hasDefaultRouteV6 = false,
        routes = emptyList(),
        dnsServers = emptyList(),
        capabilities = null,
        error = error,
    )

    /**
     * 纯 IP 可达性：逐个大陆目标、逐个端口尝试，任意一个连通即算通。
     * 单个主机或端口被拦不足以判定链路不通，因此把每个目标的结果都记下来。
     */
    private fun probeIpReachability(network: Network?): ProbeResult {
        val details = mutableListOf<String>()
        var successes = 0
        var lastError: String? =
            if (network == null) "not bound to a cellular network, used the default route" else null

        for ((ip, label) in IP_TARGETS) {
            var reached = false
            for (port in IP_PORTS) {
                val error = tryConnect(network, InetAddress.getByName(ip), port)
                if (error == null) {
                    details += "$label $ip:$port ok"
                    reached = true
                    break
                }
                details += "$label $ip:$port failed ($error)"
                lastError = error
            }
            if (reached) successes++
        }
        return ProbeResult(
            label = "ip",
            target = IP_TARGETS.joinToString { it.first },
            attempts = IP_TARGETS.size,
            successes = successes,
            lastError = lastError,
            boundToCellular = network != null,
            details = details,
            // 一次都没通、且失败全是绑定被拒：探测没成立，不构成网络结论。
            unusable = successes == 0 && details.isNotEmpty() && details.all { isBindDenied(it) },
        )
    }

    /**
     * 域名可达性：经蜂窝 Network 解析大陆域名再连接。
     * 必须用该 Network 解析，否则测的是默认网络（可能是 Wi-Fi）的解析器。
     */
    private fun probeDnsResolution(network: Network?): ProbeResult {
        val details = mutableListOf<String>()
        var successes = 0
        var lastError: String? =
            if (network == null) "not bound to a cellular network, used the default route" else null

        for (host in DNS_TARGETS) {
            val resolved = try {
                network?.getAllByName(host)?.firstOrNull() ?: InetAddress.getByName(host)
            } catch (t: Throwable) {
                val err = describe(t)
                details += "$host resolve failed ($err)"
                lastError = err
                null
            }
            if (resolved == null) continue
            val error = tryConnect(network, resolved, DNS_PORT)
            if (error == null) {
                details += "$host -> ${resolved.hostAddress} ok"
                successes++
            } else {
                details += "$host -> ${resolved.hostAddress} connect failed ($error)"
                lastError = error
            }
        }
        return ProbeResult(
            label = "dns",
            target = DNS_TARGETS.joinToString(),
            attempts = DNS_TARGETS.size,
            successes = successes,
            lastError = lastError,
            boundToCellular = network != null,
            details = details,
            unusable = successes == 0 && details.isNotEmpty() && details.all { isBindDenied(it) },
        )
    }

    /** @return null 表示连通；否则返回失败原因。 */
    private fun tryConnect(network: Network?, address: InetAddress, port: Int): String? = try {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { it.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS) }
        null
    } catch (t: Throwable) {
        describe(t)
    }

    /**
     * 把原始状态归纳成文档要求能区分的几种典型故障。
     */
    private fun buildVerdict(
        link: LinkSnapshot,
        ip: ProbeResult,
        dns: ProbeResult,
    ): String = when {
        // 读不到链路状态时不给任何网络结论：那是工具自身的问题，
        // 报成「没有蜂窝网络」会把排查引向完全错误的方向。
        link.unreadable ->
            "LINK_STATE_UNREADABLE: 读不到链路状态（${link.error ?: "unknown"}）；" +
                "以下探测走的是默认路由，不能作为蜂窝链路证据：" +
                "ip ${ip.successes}/${ip.attempts}, dns ${dns.successes}/${dns.attempts}"

        // 绑定被拒时，所有探测结果都是无效的，绝不能据此下网络结论。
        ip.unusable ->
            "PROBE_BIND_DENIED: 无法把探测 socket 绑定到蜂窝网络（EPERM）。" +
                "这是本应用的权限/申请方式问题，不是网络故障 —— " +
                "本次 IP 与域名结果均无效，请勿据此判断链路"

        !link.hasCellularNetwork ->
            "NO_CELLULAR_NETWORK: Connectivity 里没有蜂窝网络"

        link.ipv4.isEmpty() && link.ipv6.isEmpty() ->
            "NO_IP_ADDRESS: 蜂窝网络存在但没有 IP 地址"

        !link.hasDefaultRouteV4 && !link.hasDefaultRouteV6 ->
            "NO_DEFAULT_ROUTE: 有 IP 地址但没有默认路由"

        // VALIDATED=true 说明 Android 自己的连通性校验通过过，此刻却连不上任何
        // 大陆公共解析器 —— 两者矛盾本身就是线索，不能只报其中一半。
        !ip.ok && link.validated == true ->
            "IP_UNREACHABLE_WHILE_VALIDATED: 有路由且 Android 判定 VALIDATED，" +
                "但当前连不上任何大陆公共 IP（校验通过与实际不通存在矛盾，" +
                "可能是链路刚劣化或仅特定路径不通）"

        !ip.ok ->
            "IP_UNREACHABLE: 有路由但纯 IP 不通"

        !dns.ok && link.dnsServers.isEmpty() ->
            "DNS_FAILED_NO_RESOLVER: 纯 IP 可达，但该链路没有任何 DNS 服务器，域名解析失败"

        !dns.ok ->
            "DNS_FAILED: 纯 IP 可达但域名解析/连接失败"

        link.validated == false ->
            "NOT_VALIDATED: 链路可用但 Android 未判定 VALIDATED"

        else -> "OK: 蜂窝链路各项检查通过"
    }
}

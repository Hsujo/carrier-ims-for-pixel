package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private const val CONNECT_TIMEOUT_MILLIS = 3_000
    private const val PROBE_ATTEMPTS = 3
    private const val PROBE_PORT = 443
    private const val IP_TARGET = "223.5.5.5"
    private const val DNS_TARGET = "www.baidu.com"

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
        val lookup = findCellularNetwork(context)
        val cellular = lookup.getOrNull()
        val link = collectLink(context, lookup)

        // 纯 IP 可达性：绕开 DNS，直接连一个公网 IP。
        val ipProbe = probeConnect("ip", IP_TARGET, cellular) { InetAddress.getByName(IP_TARGET) }
        // 域名解析：与纯 IP 分开，才能区分「IP 通但 DNS 挂了」。
        // 必须经由蜂窝 Network 解析，否则测的是默认网络（可能是 Wi-Fi）的解析器。
        val dnsProbe = probeConnect("dns", DNS_TARGET, cellular) {
            cellular?.getAllByName(DNS_TARGET)?.firstOrNull() ?: InetAddress.getByName(DNS_TARGET)
        }
        Result(link, ipProbe, dnsProbe, buildVerdict(link, ipProbe, dnsProbe))
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
     * 有限次数的连接测试，尽量绑定到蜂窝 Network。
     * 拿不到蜂窝 Network 时退回默认路由，并在结果里标明，避免被当成蜂窝链路证据。
     */
    private fun probeConnect(
        label: String,
        target: String,
        network: Network?,
        resolve: () -> InetAddress,
    ): ProbeResult {
        var successes = 0
        var lastError: String? =
            if (network == null) "not bound to a cellular network, used the default route" else null
        repeat(PROBE_ATTEMPTS) {
            try {
                val address = resolve()
                val socket = network?.socketFactory?.createSocket() ?: Socket()
                socket.use { it.connect(InetSocketAddress(address, PROBE_PORT), CONNECT_TIMEOUT_MILLIS) }
                successes++
            } catch (t: Throwable) {
                lastError = describe(t)
            }
        }
        return ProbeResult(
            label = label,
            target = target,
            attempts = PROBE_ATTEMPTS,
            successes = successes,
            lastError = lastError,
            boundToCellular = network != null,
        )
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

        !link.hasCellularNetwork ->
            "NO_CELLULAR_NETWORK: Connectivity 里没有蜂窝网络"

        link.ipv4.isEmpty() && link.ipv6.isEmpty() ->
            "NO_IP_ADDRESS: 蜂窝网络存在但没有 IP 地址"

        !link.hasDefaultRouteV4 && !link.hasDefaultRouteV6 ->
            "NO_DEFAULT_ROUTE: 有 IP 地址但没有默认路由"

        !ip.ok ->
            "IP_UNREACHABLE: 有路由但纯 IP 不通"

        !dns.ok ->
            "DNS_FAILED: 纯 IP 可达但域名解析/连接失败"

        link.validated == false ->
            "NOT_VALIDATED: 链路可用但 Android 未判定 VALIDATED"

        else -> "OK: 蜂窝链路各项检查通过"
    }
}

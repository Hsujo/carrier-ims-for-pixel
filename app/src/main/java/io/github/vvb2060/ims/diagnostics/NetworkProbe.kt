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
 */
object NetworkProbe {
    private const val TAG = "NetworkProbe"
    private const val CONNECT_TIMEOUT_MILLIS = 3_000
    private const val PROBE_ATTEMPTS = 3

    data class LinkSnapshot(
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
     *
     * 任何一步失败都记录进结果，不抛出，保证快照仍能生成。
     */
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val link = collectLink(context)
        val cellular = findCellularNetwork(context)
        // 纯 IP 可达性：绕开 DNS，直接连一个公网 IP。
        val ipProbe = probeConnect("ip", "223.5.5.5", 443, cellular) { InetAddress.getByName("223.5.5.5") }
        // 域名解析：与纯 IP 分开，才能区分「IP 通但 DNS 挂了」。
        // 必须经由蜂窝 Network 解析，否则测的是默认网络（可能是 Wi-Fi）的解析器。
        val dnsProbe = probeConnect("dns", "www.baidu.com", 443, cellular) {
            cellular?.getAllByName("www.baidu.com")?.firstOrNull()
                ?: InetAddress.getByName("www.baidu.com")
        }
        Result(link, ipProbe, dnsProbe, buildVerdict(link, ipProbe, dnsProbe))
    }

    private fun findCellularNetwork(context: Context): Network? {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findCellularNetwork failed", t)
            null
        }
    }

    private fun collectLink(context: Context): LinkSnapshot {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return emptyLink("ConnectivityManager unavailable")
            val network = findCellularNetwork(context)
                ?: return emptyLink(null).copy(hasCellularNetwork = false)
            val caps = cm.getNetworkCapabilities(network)
            val lp: LinkProperties? = cm.getLinkProperties(network)
            val addresses = lp?.linkAddresses.orEmpty()
            val routes = lp?.routes.orEmpty()
            LinkSnapshot(
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
            emptyLink("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    private fun emptyLink(error: String?) = LinkSnapshot(
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
     * 有限次数的连接测试，全部绑定到蜂窝 Network。
     * 拿不到蜂窝 Network 时退回默认路由，并在结果里注明。
     */
    private fun probeConnect(
        label: String,
        target: String,
        port: Int,
        network: Network?,
        resolve: () -> InetAddress,
    ): ProbeResult {
        var successes = 0
        var lastError: String? = if (network == null) "no cellular network, used default route" else null
        repeat(PROBE_ATTEMPTS) {
            try {
                val address = resolve()
                val socket = network?.socketFactory?.createSocket() ?: Socket()
                socket.use {
                    it.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                }
                successes++
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            }
        }
        return ProbeResult(label, target, PROBE_ATTEMPTS, successes, lastError)
    }

    /**
     * 把原始状态归纳成文档要求能区分的几种典型故障。
     */
    private fun buildVerdict(
        link: LinkSnapshot,
        ip: ProbeResult,
        dns: ProbeResult,
    ): String = when {
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

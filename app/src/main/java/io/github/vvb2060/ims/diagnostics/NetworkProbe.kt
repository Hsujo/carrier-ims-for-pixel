package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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

    /** 时延采样次数与单次上限：够看出尾部发散，又不至于让现场等太久。 */
    private const val LATENCY_SAMPLES = 6
    private const val LATENCY_TIMEOUT_MILLIS = 6_000

    /** 申请蜂窝网络的等待上限。 */
    private const val REQUEST_NETWORK_TIMEOUT_MILLIS = 8_000

    /**
     * 探测强度。
     *
     * 后台监测和现场快照的取舍完全不同：快照只跑一次，宁可慢也要证据齐；
     * 监测每隔十几秒跑一次，一次跑多久直接决定了采样周期
     * —— 实测「15 秒间隔」的真实周期中位数是 27.8 秒，最长 76.6 秒，
     * 多出来的全是探测自身耗时，而且**链路越差探测越慢、采样越稀**，
     * 恰好在最需要密集采样的时候把分辨率丢掉了。
     */
    enum class Profile(
        val latencySamples: Int,
        val latencyTimeoutMillis: Int,
        /** 时延探测总预算：到点即停，已采到的样本照常保留。 */
        val latencyBudgetMillis: Long,
        val ipTargetCount: Int,
        val ipPortCount: Int,
        val dnsEnabled: Boolean,
        val requestNetworkTimeoutMillis: Int,
    ) {
        /** 现场快照：证据优先。 */
        FULL(
            latencySamples = LATENCY_SAMPLES,
            latencyTimeoutMillis = LATENCY_TIMEOUT_MILLIS,
            latencyBudgetMillis = 40_000,
            ipTargetCount = 3,
            ipPortCount = 2,
            dnsEnabled = true,
            requestNetworkTimeoutMillis = REQUEST_NETWORK_TIMEOUT_MILLIS,
        ),

        /**
         * 后台监测：节奏优先。
         *
         * 单次超时上限**不下调** —— 要测的就是秒级停顿，超时调到 2 秒
         * 等于把 5736ms 的抖动截断成 2000ms，signal 本身就没了。
         * 控制耗时靠总预算：链路好时 5 个样本几百毫秒就跑完，
         * 链路坏时第一个样本就吃掉预算，样本数变少但那个大值被如实记下。
         */
        MONITOR(
            latencySamples = 5,
            latencyTimeoutMillis = LATENCY_TIMEOUT_MILLIS,
            latencyBudgetMillis = 8_000,
            ipTargetCount = 1,
            ipPortCount = 1,
            dnsEnabled = false,
            requestNetworkTimeoutMillis = 5_000,
        ),
    }

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
        /** 该链路实际属于哪张 SIM；null 表示读不到（无载波特权时会被系统隐藏）。 */
        val subId: Int?,
        /** 该 PDN 是否带 INTERNET 能力。false 多半是 IMS 专用 PDN，不能当上网链路看。 */
        val hasInternetCapability: Boolean,
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

        /**
         * 这项探测根本没跑（剖面里关掉了），因此既不是「通」也不是「不通」。
         *
         * 没有这个区分就会出现：MONITOR 剖面关掉 DNS 探测后返回 0/0，
         * 而 `!ok` 恒为真 —— 实测 1223 条采样里 1217 条被判成 DNS_FAILED，
         * 判定列整列作废。「没测」必须和「测了没通」分开。
         */
        val skipped: Boolean get() = attempts == 0
    }

    /**
     * 时延分布。
     *
     * 只测通断无法发现 NSA 下的典型劣化：NR 腿质量差时反复重传再回落 LTE，
     * 连接照样成功、延迟中位数也正常，但尾部延迟极度发散
     * （实测 5G 抖动 4663ms vs 4G 46.9ms）。抖动才是这类问题的指纹。
     */
    data class LatencyResult(
        val target: String,
        val samples: List<Long>,
        val failures: Int,
        val boundToCellular: Boolean,
        /**
         * 被超时截断的样本数。
         *
         * 超时意味着「RTT >= 超时上限」，是这次测量里最重要的一类观测。
         * 早先的实现把它当失败丢掉，于是链路最差的时刻反而没有样本进入抖动计算
         * —— 抖动被系统性地低估。现在按超时值计入 samples，并单独计数，
         * 这样报出来的抖动是下界而不是错值。
         */
        val censored: Int = 0,
        /** 实际用掉的探测时间，用于核对采样周期。 */
        val elapsedMillis: Long = 0,
    ) {
        val minMs: Long? get() = samples.minOrNull()
        val maxMs: Long? get() = samples.maxOrNull()
        val avgMs: Long? get() = samples.takeIf { it.isNotEmpty() }?.average()?.toLong()

        /** 极差：最能反映「偶发的秒级卡顿」，比标准差更贴近体感。 */
        val jitterMs: Long? get() = if (samples.size >= 2) (maxMs!! - minMs!!) else null

        /** 含被截断样本时，抖动只是下界。 */
        val jitterIsLowerBound: Boolean get() = censored > 0

        val verdict: String
            get() {
                val j = jitterMs ?: return UNKNOWN_TEXT
                val ge = if (jitterIsLowerBound) ">=" else ""
                return when {
                    j >= 2000 -> "SEVERE_JITTER ($ge${j}ms，存在秒级停顿)"
                    j >= 500 -> "HIGH_JITTER ($ge${j}ms)"
                    j >= 150 -> "MODERATE_JITTER ($ge${j}ms)"
                    else -> "STABLE ($ge${j}ms)"
                }
            }
    }

    private const val UNKNOWN_TEXT = "UNKNOWN"

    data class Result(
        val link: LinkSnapshot,
        val ipReachability: ProbeResult,
        val dnsResolution: ProbeResult,
        val latency: LatencyResult?,
        val verdict: String,
        /** 目标 subId 与实际被探测网络的 subId，不一致时结论不属于目标卡。 */
        val targetSubId: Int?,
        val probedSubId: Int?,
    ) {
        val subIdMismatch: Boolean
            get() = targetSubId != null && probedSubId != null && targetSubId != probedSubId
    }

    /**
     * 采集蜂窝链路状态并做有限次数的连通性测试。
     * 任何一步失败都记录进结果，不抛出，保证快照仍能生成。
     */
    /**
     * @param targetSubId 目标 SIM。双卡时必须指定，否则可能测到另一张卡的链路，
     *        得出的结论与被诊断的卡无关。
     */
    suspend fun run(
        context: Context,
        targetSubId: Int? = null,
        profile: Profile = Profile.FULL,
    ): Result = withContext(Dispatchers.IO) {
        // 链路状态只需要 ACCESS_NETWORK_STATE，枚举即可读到。
        val lookup = findCellularNetwork(context, targetSubId)
        val link = collectLink(context, lookup)

        // 但要把 socket 绑上去，必须显式 requestNetwork 让系统授予使用权，
        // 否则 netd 会以 EPERM 拒绝绑定（枚举得到的 Network 不等于可用）。
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val requested = if (cm != null) {
                val (network, cb) = requestCellularNetwork(cm, targetSubId, profile)
                callback = cb
                network
            } else {
                null
            }
            val cellular = requested ?: lookup.getOrNull()
            val probedSubId = cellular?.let { subIdOf(cm, it) }
            // 两类探测互不依赖，并行执行以缩短现场等待时间。
            val triple = coroutineScope {
                val ip = async { probeIpReachability(cellular, profile) }
                val dns = async { probeDnsResolution(cellular, profile) }
                val lat = async { probeLatency(cellular, profile) }
                Triple(ip.await(), dns.await(), lat.await())
            }
            val (ipProbe, dnsProbe, latency) = triple
            Result(
                link = link,
                ipReachability = ipProbe,
                dnsResolution = dnsProbe,
                latency = latency,
                verdict = buildVerdict(link, ipProbe, dnsProbe, latency, targetSubId, probedSubId),
                targetSubId = targetSubId,
                probedSubId = probedSubId,
            )
        } finally {
            callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        }
    }

    /** 读出某个网络归属的 subId；无载波特权时系统可能隐藏 specifier，返回 null。 */
    private fun subIdOf(cm: ConnectivityManager?, network: Network): Int? = runCatching {
        val spec = cm?.getNetworkCapabilities(network)?.networkSpecifier
        (spec as? TelephonyNetworkSpecifier)?.subscriptionId
    }.getOrNull()

    /**
     * 显式申请一个可用的蜂窝网络。
     *
     * 优先带 TelephonyNetworkSpecifier 精确申请目标卡；系统不满足该请求时
     * 退回不带 specifier 的请求，此时可能拿到另一张卡，由调用方比对 subId 后提示。
     */
    private suspend fun requestCellularNetwork(
        cm: ConnectivityManager,
        targetSubId: Int?,
        profile: Profile = Profile.FULL,
    ): Pair<Network?, ConnectivityManager.NetworkCallback?> {
        if (targetSubId != null && targetSubId >= 0) {
            val specific = runCatching {
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(
                        TelephonyNetworkSpecifier.Builder().setSubscriptionId(targetSubId).build()
                    )
                    .build()
            }.getOrNull()
            if (specific != null) {
                val result = awaitNetwork(cm, specific, profile.requestNetworkTimeoutMillis)
                if (result.first != null) return result
                result.second?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                Log.i(TAG, "specific request for subId=$targetSubId unavailable, falling back")
            }
        }
        val generic = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return awaitNetwork(cm, generic, profile.requestNetworkTimeoutMillis)
    }

    private suspend fun awaitNetwork(
        cm: ConnectivityManager,
        request: NetworkRequest,
        timeoutMillis: Int,
    ): Pair<Network?, ConnectivityManager.NetworkCallback?> = try {
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
                cm.requestNetwork(request, cb, timeoutMillis)
            } catch (t: Throwable) {
                Log.w(TAG, "requestNetwork failed", t)
                cont.resume(null to null)
            }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "awaitNetwork failed", t)
        null to null
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
    private fun findCellularNetwork(
        context: Context,
        targetSubId: Int?,
    ): kotlin.Result<Network?> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager unavailable")
        val cellular = cm.allNetworks.filter { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
        // 一张 SIM 通常同时存在多条 PDN：IMS 专用（只有 IMS/MMTEL 能力、
        // 常见 IPv6-only 且无 DNS）与上网用（带 INTERNET 能力）。
        // 诊断「有信号没数据」要看的是后者，取错会把 IMS PDN 的正常状态误读成故障。
        fun hasInternet(network: Network) = cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val matchesTarget = { network: Network -> subIdOf(cm, network) == targetSubId }
        cellular.firstOrNull { matchesTarget(it) && hasInternet(it) }
            ?: cellular.firstOrNull { hasInternet(it) }
            ?: cellular.firstOrNull { matchesTarget(it) }
            ?: cellular.firstOrNull()
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
                subId = (caps?.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId,
                hasInternetCapability =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
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
        subId = null,
        hasInternetCapability = false,
        error = error,
    )

    /**
     * 纯 IP 可达性：逐个大陆目标、逐个端口尝试，任意一个连通即算通。
     * 单个主机或端口被拦不足以判定链路不通，因此把每个目标的结果都记下来。
     */
    private fun probeIpReachability(network: Network?, profile: Profile): ProbeResult {
        val details = mutableListOf<String>()
        var successes = 0
        var lastError: String? =
            if (network == null) "not bound to a cellular network, used the default route" else null
        // 后台监测只取一个目标一个端口：多目标是为了在现场分辨「个别目标被拦」，
        // 而监测要的是节奏，通断本身有时延探测兜底。
        val targets = IP_TARGETS.take(profile.ipTargetCount)
        val ports = IP_PORTS.take(profile.ipPortCount)

        for ((ip, label) in targets) {
            var reached = false
            for (port in ports) {
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
            target = targets.joinToString { it.first },
            attempts = targets.size,
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
    private fun probeDnsResolution(network: Network?, profile: Profile): ProbeResult {
        // 后台监测不解析域名：DNS 结果在这个故障里从来没变过（一直正常），
        // 每次却要多花一次解析加一次握手，只是在拖慢采样。
        if (!profile.dnsEnabled) {
            return ProbeResult(
                label = "dns",
                target = "(skipped in monitor profile)",
                attempts = 0,
                successes = 0,
                lastError = null,
                boundToCellular = network != null,
                details = emptyList(),
            )
        }
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

    /**
     * 连续多次连接同一目标，记录每次 RTT。
     *
     * 串行而非并行：并行发出的连接共享同一瞬间，测不出「前后两次差了几秒」，
     * 而抖动正是这个故障的指纹。
     *
     * 两处约束缺一不可：
     * - **总预算**限制整段耗时，否则链路差时单次探测能拖到几十秒，
     *   把后台采样周期一起拖垮（实测最长 76.6 秒）；
     * - **超时按下界计入**而不是丢弃，否则最差的时刻反而没有样本。
     */
    private fun probeLatency(network: Network?, profile: Profile): LatencyResult {
        val target = IP_TARGETS.first()
        val samples = mutableListOf<Long>()
        var failures = 0
        var censored = 0
        val startedAll = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAll) / 1_000_000
        val address = runCatching { InetAddress.getByName(target.first) }.getOrNull()
            ?: return LatencyResult(
                target.first, emptyList(), profile.latencySamples, network != null,
                elapsedMillis = elapsed(),
            )

        repeat(profile.latencySamples) {
            // 预算用尽即停：已采到的样本照常保留，样本少也好过采样周期失控。
            if (elapsed() >= profile.latencyBudgetMillis) return@repeat
            val started = System.nanoTime()
            val failure = connectFailure(
                network, address, IP_PORTS.first(), profile.latencyTimeoutMillis
            )
            when {
                failure == null -> samples += (System.nanoTime() - started) / 1_000_000
                // 超时 = RTT 至少有这么大，这是观测不是缺失。
                failure is SocketTimeoutException -> {
                    samples += profile.latencyTimeoutMillis.toLong()
                    censored++
                }
                else -> failures++
            }
        }
        return LatencyResult(
            target = target.first,
            samples = samples,
            failures = failures,
            boundToCellular = network != null,
            censored = censored,
            elapsedMillis = elapsed(),
        )
    }

    /** @return null 表示连通；否则返回失败原因。 */
    private fun tryConnect(
        network: Network?,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    ): String? = connectFailure(network, address, port, timeoutMillis)?.let { describe(it) }

    /**
     * @return null 表示连通；否则返回原始异常。
     *
     * 保留异常本体而非只给文本：时延探测必须能把「超时」和「立刻被拒」
     * 区分开 —— 前者是 RTT 的下界观测，后者才是真正的失败。
     */
    private fun connectFailure(
        network: Network?,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ): Throwable? = try {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { it.connect(InetSocketAddress(address, port), timeoutMillis) }
        null
    } catch (t: Throwable) {
        t
    }

    /**
     * 把原始状态归纳成文档要求能区分的几种典型故障。
     */
    private fun buildVerdict(
        link: LinkSnapshot,
        ip: ProbeResult,
        dns: ProbeResult,
        latency: LatencyResult?,
        targetSubId: Int?,
        probedSubId: Int?,
    ): String = when {
        // 测到的不是目标卡时，任何结论都与被诊断的 SIM 无关。
        targetSubId != null && probedSubId != null && targetSubId != probedSubId ->
            "WRONG_SIM_PROBED: 实际测到的是 subId=$probedSubId 的网络，" +
                "而目标是 subId=$targetSubId；本次结果不属于目标卡，请勿据此判断"

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

        // IMS 专用 PDN 本来就没有 DNS、常为 IPv6-only，按上网链路去判会得出假故障。
        !link.hasInternetCapability ->
            "NO_INTERNET_PDN: 目标 SIM 上只找到不带 INTERNET 能力的 PDN" +
                "（通常是 IMS 专用通道），未找到用于上网的数据连接"

        link.ipv4.isEmpty() && link.ipv6.isEmpty() ->
            "NO_IP_ADDRESS: 蜂窝网络存在但没有 IP 地址"

        !link.hasDefaultRouteV4 && !link.hasDefaultRouteV6 ->
            "NO_DEFAULT_ROUTE: 有 IP 地址但没有默认路由"

        // VALIDATED=true 说明 Android 自己的连通性校验通过过，此刻却连不上任何
        // 大陆公共解析器 —— 两者矛盾本身就是线索，不能只报其中一半。
        !ip.skipped && !ip.ok && link.validated == true ->
            "IP_UNREACHABLE_WHILE_VALIDATED: 有路由且 Android 判定 VALIDATED，" +
                "但当前连不上任何大陆公共 IP（校验通过与实际不通存在矛盾，" +
                "可能是链路刚劣化或仅特定路径不通）"

        !ip.skipped && !ip.ok ->
            "IP_UNREACHABLE: 有路由但纯 IP 不通"

        !dns.skipped && !dns.ok && link.dnsServers.isEmpty() ->
            "DNS_FAILED_NO_RESOLVER: 纯 IP 可达，但该链路没有任何 DNS 服务器，域名解析失败"

        !dns.skipped && !dns.ok ->
            "DNS_FAILED: 纯 IP 可达但域名解析/连接失败"

        link.validated == false ->
            "NOT_VALIDATED: 链路可用但 Android 未判定 VALIDATED"

        // 全部连得上但尾部延迟发散：NSA 下 NR 腿质量差的典型表现，
        // 只看通断会漏掉，而这恰恰是「满格却卡」的直接成因。
        (latency?.jitterMs ?: 0L) >= 2000L ->
            "USABLE_BUT_SEVERE_JITTER: 连接均成功，但抖动 ${latency?.jitterMs}ms 存在秒级停顿" +
                "（RTT ${latency?.minMs}~${latency?.maxMs}ms）"

        (latency?.jitterMs ?: 0L) >= 500L ->
            "USABLE_BUT_HIGH_JITTER: 连接均成功，但抖动 ${latency?.jitterMs}ms" +
                "（RTT ${latency?.minMs}~${latency?.maxMs}ms）"

        else -> "OK: 蜂窝链路各项检查通过" +
            (latency?.jitterMs?.let { "（RTT ${latency.minMs}~${latency.maxMs}ms，抖动 ${it}ms）" } ?: "")
    }
}

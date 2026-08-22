package io.github.vvb2060.ims.shell;

/**
 * 在 Shizuku 派生的 shell uid 进程中执行命令。
 *
 * 应用自身的 uid 读不到 radio 日志缓冲区，也无权运行 dumpsys，
 * 因此诊断采集必须经由该服务。
 */
interface IShellService {
    /**
     * 执行一条命令。
     *
     * @param command 命令与参数，不经过 shell 解析，避免注入。
     * @param timeoutMillis 超时上限，超时后强制结束进程。
     * @param maxOutputBytes stdout/stderr 各自的截断上限，防止无上限增长。
     * @return 长度为 3 的数组：[exitCode, stdout, stderr]。
     *         exitCode 为 "timeout" 表示超时，"error" 表示无法启动。
     */
    String[] exec(in String[] command, int timeoutMillis, int maxOutputBytes);

    /** 结束该服务进程。 */
    void destroy();
}

package io.github.vvb2060.ims.shell;

/**
 * 在 Shizuku 派生的 shell uid 进程中执行命令。
 *
 * 应用自身的 uid 读不到 radio 日志缓冲区，也无权运行 dumpsys，
 * 因此诊断采集必须经由该服务。
 */
interface IShellService {
    /**
     * 执行一条命令，输出直接写入调用方提供的文件描述符。
     *
     * 刻意不通过返回值回传输出：Binder 单次事务上限约 1MB，
     * dumpsys telephony.registry 与 radio 日志都会超过，
     * 用返回值会以 DeadObjectException / binder buffer full 失败。
     *
     * @param command 命令与参数，不经过 shell 解析，避免注入。
     * @param stdoutFd 接收 stdout 的可写描述符，由调用方创建与关闭。
     * @param stderrFd 接收 stderr 的可写描述符。
     * @param timeoutMillis 超时上限，超时后强制结束进程。
     * @param maxOutputBytes 各流写入上限，防止无上限增长。
     * @return exit code 的字符串形式；"timeout" 表示超时，"error" 表示无法启动。
     */
    String execToFd(in String[] command, in ParcelFileDescriptor stdoutFd,
                    in ParcelFileDescriptor stderrFd, int timeoutMillis, int maxOutputBytes);

    /** 结束该服务进程。 */
    void destroy();
}

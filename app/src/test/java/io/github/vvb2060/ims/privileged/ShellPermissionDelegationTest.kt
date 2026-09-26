package io.github.vvb2060.ims.privileged

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellPermissionDelegationTest {
    @Test
    fun hiddenApiIncompatibilityIsTreatedAsCompatFailure() {
        // Android 17 上 stopDelegateShellPermissionIdentity() 缺失时的实际异常类型。
        assertTrue(ShellPermissionDelegation.isCompatFailure(NoSuchMethodError("stopDelegate...")))
        assertTrue(ShellPermissionDelegation.isCompatFailure(AbstractMethodError()))
        assertTrue(ShellPermissionDelegation.isCompatFailure(NoSuchMethodException()))
        assertTrue(ShellPermissionDelegation.isCompatFailure(IllegalAccessException()))
        assertTrue(ShellPermissionDelegation.isCompatFailure(SecurityException()))
        assertTrue(ShellPermissionDelegation.isCompatFailure(UnsupportedOperationException()))
    }

    @Test
    fun programmingErrorsAreNotSwallowed() {
        // 这些说明代码本身有问题，必须继续抛出，不能被当作兼容性失败降级处理。
        assertFalse(ShellPermissionDelegation.isCompatFailure(NullPointerException()))
        assertFalse(ShellPermissionDelegation.isCompatFailure(IllegalStateException()))
        assertFalse(ShellPermissionDelegation.isCompatFailure(IllegalArgumentException()))
        assertFalse(ShellPermissionDelegation.isCompatFailure(OutOfMemoryError()))
    }
}

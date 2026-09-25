package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureConfigMapperTest {
    @Test
    fun fiveGPlusIconIsOnlyReportedWhenWrittenValuesMatch() {
        assertTrue(
            FeatureConfigMapper.isFiveGPlusIconApplied(
                thresholdKhz = FeatureConfigMapper.NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA,
                iconConfiguration = FeatureConfigMapper.NR_ICON_CONFIGURATION_5GA,
            )
        )
    }

    @Test
    fun fiveGPlusIconIsOffForCarrierDefaults() {
        // getConfigForSubId 总会返回默认值：阈值 0、默认图标配置
        assertFalse(
            FeatureConfigMapper.isFiveGPlusIconApplied(
                thresholdKhz = 0,
                iconConfiguration = "connected_mmwave:5G_Plus,connected:5G",
            )
        )
        assertFalse(FeatureConfigMapper.isFiveGPlusIconApplied(thresholdKhz = null, iconConfiguration = null))
    }

    @Test
    fun fiveGPlusIconNeedsBothThresholdAndIconConfiguration() {
        assertFalse(
            FeatureConfigMapper.isFiveGPlusIconApplied(
                thresholdKhz = FeatureConfigMapper.NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA,
                iconConfiguration = "",
            )
        )
        assertFalse(
            FeatureConfigMapper.isFiveGPlusIconApplied(
                thresholdKhz = 0,
                iconConfiguration = FeatureConfigMapper.NR_ICON_CONFIGURATION_5GA,
            )
        )
    }
}

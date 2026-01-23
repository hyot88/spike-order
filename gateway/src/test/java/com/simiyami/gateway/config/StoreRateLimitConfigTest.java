package com.simiyami.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoreRateLimitConfigTest {

    private StoreRateLimitConfig config;

    @BeforeEach
    void setUp() {
        config = new StoreRateLimitConfig();
    }

    @Test
    @DisplayName("기본 Rate Limit은 5000")
    void shouldReturnDefaultLimit() {
        assertThat(config.getDefaultLimit()).isEqualTo(5000);
    }

    @Test
    @DisplayName("존재하지 않는 가게는 기본값 5000 반환")
    void shouldReturnDefaultLimitForUnknownStore() {
        assertThat(config.getLimit("unknown-store")).isEqualTo(5000);
    }

    @Test
    @DisplayName("가게별 커스텀 Limit 설정 가능")
    void shouldSetCustomLimit() {
        config.setLimit("store-event", 10000);
        assertThat(config.getLimit("store-event")).isEqualTo(10000);
    }

    @Test
    @DisplayName("여러 가게에 서로 다른 Limit 설정 가능")
    void shouldSetDifferentLimitsPerStore() {
        config.setLimit("store-A", 10000);
        config.setLimit("store-B", 15000);
        config.setLimit("store-C", 20000);

        assertThat(config.getLimit("store-A")).isEqualTo(10000);
        assertThat(config.getLimit("store-B")).isEqualTo(15000);
        assertThat(config.getLimit("store-C")).isEqualTo(20000);
    }

    @Test
    @DisplayName("기본값으로 리셋 가능")
    void shouldResetToDefault() {
        config.setLimit("store-reset", 20000);
        assertThat(config.getLimit("store-reset")).isEqualTo(20000);

        config.resetToDefault("store-reset");
        assertThat(config.getLimit("store-reset")).isEqualTo(5000);
    }

    @Test
    @DisplayName("존재하지 않는 가게 리셋해도 에러 없음")
    void shouldNotFailWhenResettingUnknownStore() {
        config.resetToDefault("non-existent-store");
        assertThat(config.getLimit("non-existent-store")).isEqualTo(5000);
    }

    @Test
    @DisplayName("모든 커스텀 Limit 조회 가능")
    void shouldGetAllCustomLimits() {
        config.setLimit("store-A", 10000);
        config.setLimit("store-B", 15000);

        Map<String, Long> limits = config.getAllCustomLimits();

        assertThat(limits).hasSize(2);
        assertThat(limits.get("store-A")).isEqualTo(10000);
        assertThat(limits.get("store-B")).isEqualTo(15000);
    }

    @Test
    @DisplayName("커스텀 Limit이 없으면 빈 맵 반환")
    void shouldReturnEmptyMapWhenNoCustomLimits() {
        Map<String, Long> limits = config.getAllCustomLimits();
        assertThat(limits).isEmpty();
    }

    @Test
    @DisplayName("반환된 맵 수정해도 원본에 영향 없음")
    void shouldReturnDefensiveCopy() {
        config.setLimit("store-A", 10000);

        Map<String, Long> limits = config.getAllCustomLimits();
        limits.put("store-B", 20000L);

        assertThat(config.getAllCustomLimits()).hasSize(1);
        assertThat(config.getAllCustomLimits()).doesNotContainKey("store-B");
    }
}

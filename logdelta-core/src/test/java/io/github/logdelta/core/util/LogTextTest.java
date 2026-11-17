// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogTextTest {

    @Test
    @DisplayName("extracts the message from common log layouts")
    void message() {
        assertEquals("Flag is deprecated",
                LogText.message("2026-11-12 08:49:03.117 WARN  [main] c.a.FeatureFlags - Flag is deprecated"));
        assertEquals("Order ORD-<ID> placed",
                LogText.message("<TIMESTAMP> INFO  [exec-<N>] c.a.OrderService - Order ORD-<ID> placed"));
        assertEquals("java.net.SocketTimeoutException: Read timed out",
                LogText.message("java.net.SocketTimeoutException: Read timed out"));
        assertEquals("disk almost full", LogText.message("WARNING disk almost full"));
    }
}

package com.robbe1912.hermeslite.gateway

import com.robbe1912.hermeslite.data.PushConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun aScriptRunGivesThePathOfItsReport() {
        assertEquals(
            "cron/output/9f8a2d6d0395/2026-11-24_17-15-52.md",
            reportPath("cron_output:9f8a2d6d0395:2026-11-24_17-15-52"),
        )
    }

    @Test
    fun aRunWithASessionHasNoReport() {
        assertEquals("", reportPath("local-f51f8d809087282870724c84b61a00f2511968c467"))
        assertEquals("", reportPath("cron_output:9f8a2d6d0395:"))
    }

    @Test
    fun timeReadsIsoTextAndEpochSeconds() {
        assertEquals(1795540552L, parseTime("2026-11-24T18:15:52+01:00"))
        assertEquals(1795540552L, parseTime("2026-11-24T17:15:52.02+00:00"))
        assertEquals(1795518039L, parseTime("1795518039.830655"))
        assertEquals(0L, parseTime(null))
        assertEquals(0L, parseTime("no time"))
    }

    @Test
    fun anAddressSplitsIntoHostAndPort() {
        assertEquals("192.168.50.20" to 9119, splitAddress("192.168.50.20:9119", https = false))
        assertEquals("hermes.lan" to 80, splitAddress("hermes.lan", https = false))
        assertEquals("hermes.lan" to 443, splitAddress("hermes.lan", https = true))
        assertEquals("::1" to 9119, splitAddress("[::1]:9119", https = false))
    }
}

class PushConfigTest {
    private val services = """
        {
          "project_info": {"project_number": "123456789", "project_id": "home-7f3a0", "project_name": "home"},
          "client": [
            {"client_info": {"android_client_info": {"package_name": "com.example.other"}, "mobilesdk_app_id": "1:1:android:other"},
             "api_key": [{"current_key": "AIzaOTHER"}]},
            {"client_info": {"android_client_info": {"package_name": "com.robbe1912.hermeslite"}, "mobilesdk_app_id": "1:123456789:android:abc"},
             "api_key": [{"current_key": "AIzaMINE"}]}
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheClientOfThisApp() {
        val config = PushConfig.fromServicesJson(services, "com.robbe1912.hermeslite")!!
        assertEquals("home-7f3a0", config.projectId)
        assertEquals("AIzaMINE", config.apiKey)
        assertEquals("1:123456789:android:abc", config.appId)
        assertEquals("123456789", config.senderId)
        assertTrue(config.isSetUp)
    }

    @Test
    fun fallsBackToTheFirstAndroidClient() {
        val config = PushConfig.fromServicesJson(services, "com.unknown.app")!!
        assertEquals("AIzaOTHER", config.apiKey)
    }

    @Test
    fun aFileWithoutTheValuesGivesNull() {
        assertNull(PushConfig.fromServicesJson("{\"project_info\": {\"project_id\": \"x\"}}", "com.robbe1912.hermeslite"))
        assertNull(PushConfig.fromServicesJson("not json", "com.robbe1912.hermeslite"))
    }
}

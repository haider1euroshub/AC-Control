package com.roomac.controller.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrustedUrlTest {
    private val hosts = setOf("account.example.com")

    @Test
    fun `https on the exact host or a subdomain is allowed`() {
        assertTrue(TrustedUrl.isAllowed("https://account.example.com/signup", hosts))
        assertTrue(TrustedUrl.isAllowed("https://eu.account.example.com/signup?lang=en", hosts))
        assertTrue(TrustedUrl.isAllowed("https://ACCOUNT.example.com:443/", hosts))
    }

    @Test
    fun `plain http, other schemes and malformed URLs are refused`() {
        assertFalse(TrustedUrl.isAllowed("http://account.example.com/signup", hosts))
        assertFalse(TrustedUrl.isAllowed("javascript:alert(1)", hosts))
        assertFalse(TrustedUrl.isAllowed("intent://account.example.com#Intent;end", hosts))
        assertFalse(TrustedUrl.isAllowed("file:///sdcard/x.html", hosts))
        assertFalse(TrustedUrl.isAllowed("not a url", hosts))
        assertFalse(TrustedUrl.isAllowed("", hosts))
    }

    @Test
    fun `look-alike hosts, user-info tricks and odd ports are refused`() {
        assertFalse(TrustedUrl.isAllowed("https://account.example.com.evil.test/", hosts))
        assertFalse(TrustedUrl.isAllowed("https://evilaccount.example.com/", hosts))
        assertFalse(TrustedUrl.isAllowed("https://account.example.com@evil.test/", hosts))
        assertFalse(TrustedUrl.isAllowed("https://evil.test/?next=https://account.example.com", hosts))
        assertFalse(TrustedUrl.isAllowed("https://account.example.com:8443/", hosts))
    }

    @Test
    fun `a registration page on an untrusted host cannot even be declared`() {
        try {
            WebRegistration("https://evil.test/signup", hosts, evidence = "test")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        WebRegistration("https://account.example.com/signup", hosts, evidence = "test")
    }

    @Test
    fun `by default a provider offers no web registration`() {
        assertNull(AccountFeatures().webRegistration)
    }
}

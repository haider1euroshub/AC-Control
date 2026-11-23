package com.roomac.controller.netscan

/**
 * Test-only OUI fixture with made-up vendors on made-up prefixes, so tests never depend on (or pretend
 * to be) real registry data. Prefixes are universally administered (bit 1 of the first octet clear).
 */
val TEST_OUIS: OuiTable = OuiTable.fromGenerated(
    listOf(
        OuiData.Vendor("TestPrinterCo", DeviceType.PRINTER, "00AA01"),
        OuiData.Vendor("TestSpeakerCo", DeviceType.SPEAKER, "00AA02 00aa03"),
        OuiData.Vendor("TestBoardCo", null, "00AA04")
    )
)

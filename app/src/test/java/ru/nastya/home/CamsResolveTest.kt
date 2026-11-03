package ru.nastya.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CamsResolveTest {
    // Ответ getcamerastreamstate.php для волков Миннесоты (alias 63766320c2459) утром по Центральному времени 3 ноября 2026:
    // камера работает по расписанию (operationtimewindow), до включения 2614 с.
    private val wolvesOffSchedule = """{"details":{"id":"284732","alias":"63766320c2459","streamavailable":"0","servicetype":"P",
        "streamid":null,"address":"http:\/\/s28.ipcamlive.com\/","timeshiftenabled":"1","storageenabled":"0",
        "operationtimewindowenabled":"1","operationtimewindowdata":"W10000830","operationtimewindowactive":0,
        "operationtimewindow_starttimesec":2614}}"""

    private val wolvesLive = """{"details":{"alias":"63766320c2459","streamavailable":"1","streamid":"1cx0nzpifv96rflal",
        "address":"http:\/\/s28.ipcamlive.com\/","timeshiftenabled":"1","storageenabledoncamera":"0",
        "operationtimewindowenabled":"1","operationtimewindowactive":1,"operationtimewindow_starttimesec":-1}}"""

    @Test fun offScheduleCameraReportsCountdownInsteadOfResolveFailure() {
        try {
            CamsRepository.ipcamliveStream(JSONObject(wolvesOffSchedule))
            fail("ожидалось OffScheduleException")
        } catch (e: CamsRepository.OffScheduleException) {
            assertEquals(2614, e.startsInSec)
        }
    }

    @Test fun liveCameraWithinWindowResolvesTimeshiftStream() {
        val s = CamsRepository.ipcamliveStream(JSONObject(wolvesLive))
        assertEquals("https://s28.ipcamlive.com/streams_timeshift/1cx0nzpifv96rflal/stream.m3u8", s.url)
        assertTrue(s.headers["User-Agent"]!!.contains("Chrome"))
    }

    @Test fun cameraWithoutScheduleButNoStreamIsPlainError() {
        val json = """{"details":{"streamavailable":"0","streamid":null,"address":"http://s1.ipcamlive.com/","operationtimewindowenabled":"0"}}"""
        try {
            CamsRepository.ipcamliveStream(JSONObject(json))
            fail("ожидалась ошибка")
        } catch (e: CamsRepository.OffScheduleException) {
            fail("без расписания это не OffScheduleException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("выключена"))
        }
    }

    @Test fun storageOnCameraUsesStorageDirectory() {
        val json = """{"details":{"streamavailable":"1","streamid":"abc","address":"https://s9.ipcamlive.com","storageenabledoncamera":"1","timeshiftenabled":"1"}}"""
        assertEquals("https://s9.ipcamlive.com/streams_storage/abc/stream.m3u8", CamsRepository.ipcamliveStream(JSONObject(json)).url)
    }
}

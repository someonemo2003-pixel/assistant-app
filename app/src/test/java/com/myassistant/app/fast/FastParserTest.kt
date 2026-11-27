package com.myassistant.app.fast

import com.myassistant.app.fast.LocalIntent.Alarm
import com.myassistant.app.fast.LocalIntent.AskDate
import com.myassistant.app.fast.LocalIntent.AskTime
import com.myassistant.app.fast.LocalIntent.OpenApp
import com.myassistant.app.fast.LocalIntent.Remember
import com.myassistant.app.fast.LocalIntent.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastParserTest {

    private fun p(s: String) = FastParser.parse(s)

    @Test fun alarms() {
        assertEquals(Alarm(7, 0), p("صحيني الساعة 7"))
        assertEquals(Alarm(7, 0), p("صحّيني بكرة الساعة ٧ الصبح"))
        assertEquals(Alarm(6, 30), p("منبه 6 ونص"))
        assertEquals(Alarm(21, 15), p("نبهني الساعة 9 وربع بالليل"))
        assertEquals(Alarm(7, 45), p("صحيني 8 الا ربع"))
        assertEquals(Alarm(6, 10), p("منبه 6 و 10"))
        assertEquals(Alarm(22, 30), p("منبه 22:30"))
        assertEquals(Alarm(7, 0), p("صحيني الساعة سبعة"))
        assertEquals(Alarm(13, 0), p("منبه الساعة 1 الضهر"))
        assertEquals(Alarm(7, 0), p("صحيني الساعة السابعة"))
    }

    @Test fun timers() {
        assertEquals(Timer(600), p("تايمر 10 دقايق"))
        assertEquals(Timer(600), p("اعمل مؤقت عشر دقايق"))
        assertEquals(Timer(1800), p("تايمر نص ساعة"))
        assertEquals(Timer(5400), p("مؤقت ساعة ونص"))
        assertEquals(Timer(120), p("تايمر دقيقتين"))
        assertEquals(Timer(300), p("تايمر 5"))
        assertEquals(Timer(900), p("نبهني بعد ربع ساعة"))
        assertEquals(Timer(330), p("تايمر 5 دقايق و 30 ثانية"))
    }

    @Test fun apps() {
        assertEquals(OpenApp(listOf("YouTube", "يوتيوب")), p("افتح يوتيوب"))
        assertEquals(OpenApp(listOf("WhatsApp", "الواتس")), p("افتحلي الواتس"))
        assertEquals(OpenApp(listOf("Camera", "الكاميرا")), p("افتح لي الكاميرا"))
        assertEquals(OpenApp(listOf("Shazam")), p("افتح Shazam"))
        assertNull(p("شغل اغنية عمرو دياب"))
    }

    @Test fun flashlight() {
        assertEquals(LocalIntent.Flashlight(true), p("شغل الكشاف"))
        assertEquals(LocalIntent.Flashlight(false), p("اقفل الكشاف"))
        assertEquals(LocalIntent.Flashlight(true), p("ولّع الفلاش"))
    }

    @Test fun health() {
        assertEquals(LocalIntent.Steps("today"), p("مشيت كام خطوة النهارده؟"))
        assertEquals(LocalIntent.Steps("yesterday"), p("مشيت كام خطوة امبارح"))
        assertEquals(LocalIntent.Walk(true), p("ابدأ تتبع المشي"))
        assertEquals(LocalIntent.Walk(false), p("وقف المشي"))
        assertEquals(LocalIntent.Places("today"), p("وريني أنا مشيت فين النهارده"))
        assertEquals(LocalIntent.Places("yesterday"), p("ارسملي الخريطة بتاعة امبارح"))
        assertEquals(LocalIntent.SavePlace("الجامعة"), p("سجل المكان ده باسم الجامعة"))
        assertEquals(LocalIntent.Prayed("asr"), p("صليت العصر"))
        assertEquals(LocalIntent.Prayed(null), p("صليت"))
        assertEquals(LocalIntent.PrayerTime("maghrib", true), p("فاضل قد ايه على المغرب"))
        assertEquals(LocalIntent.Habit("reading", 5.0, "pages"), p("قريت 5 صفحات"))
        assertEquals(LocalIntent.Habit("study", 120.0, "minutes"), p("ذاكرت ساعتين"))
        assertEquals(LocalIntent.Habit("water", 2.0, "cups"), p("شربت كوبايتين مية"))
        assertEquals(LocalIntent.Silent(true), p("اعمل الموبايل صامت"))
        assertEquals(LocalIntent.Silent(false), p("شيل الصامت"))
    }

    @Test fun questions() {
        assertEquals(AskTime, p("الساعة كام؟"))
        assertEquals(AskTime, p("الساعه كم"))
        assertEquals(AskDate, p("النهارده إيه؟"))
    }

    @Test fun remember() {
        assertEquals(Remember("عيد ميلاد أمي يوم 5 مارس"), p("افتكر إن عيد ميلاد أمي يوم 5 مارس"))
    }

    @Test fun phraseKeys() {
        assertEquals(FastParser.phraseKey("وضع النوم"), FastParser.phraseKey("وضع النوم لو سمحت"))
        assertEquals(FastParser.phraseKey("شغّللي الفيس"), FastParser.phraseKey("شغللي الفيس!"))
        assertEquals(listOf("7"), FastParser.numbers("صحيني ٧"))
    }

    @Test fun goesToCloud() {
        assertNull(p("اكتبلي ايميل لمديري"))
        assertNull(p("صحيني 7 وافتح يوتيوب"))
        assertNull(p("صحيني"))
        assertNull(p("ايه احسن مطعم في المعادي"))
    }

    @Test fun aboutMe() {
        assertEquals(LocalIntent.AboutMe, FastParser.parse("تعرف ايه عني"))
        assertEquals(LocalIntent.AboutMe, FastParser.parse("انت عارف عني ايه؟"))
        assertEquals(LocalIntent.AboutMe, FastParser.parse("إيه اللي تعرفه عني"))
        assertEquals(LocalIntent.AboutMe, FastParser.parse("what do you know about me"))
        assertTrue(FastParser.parse("افتكر إن عيد ميلادي 5 مايو") is LocalIntent.Remember)
    }

    @Test fun openCode() {
        assertEquals(LocalIntent.OpenCodeTask("يعمل script بايثون يطبع التاريخ"), FastParser.parse("خلّي OpenCode يعمل script بايثون يطبع التاريخ"))
        assertEquals(LocalIntent.OpenCodeTask("قولي إصدار بايثون"), FastParser.parse("opencode: قولي إصدار بايثون"))
        assertEquals(LocalIntent.OpenCodeTask("اعمل موقع بسيط"), FastParser.parse("اطلب من opencode اعمل موقع بسيط"))
        assertNull(FastParser.parse("opencode بطيء ليه؟"))
        assertEquals(LocalIntent.OpenCodeGo, FastParser.parse("نفّذ"))
        assertEquals(LocalIntent.OpenCodeGo, FastParser.parse("كمل ونفذ"))
    }
}

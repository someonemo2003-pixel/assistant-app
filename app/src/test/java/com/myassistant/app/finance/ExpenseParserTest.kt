package com.myassistant.app.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseParserTest {

    @Test fun englishCardPurchase() {
        val t = ExpenseParser.parse("Your CIB credit card ending with 1234 was charged for EGP 250.00 at TALABAT on 17/11 14:02.")
        assertNotNull(t); t!!
        assertEquals(250.0, t.amount, 0.001)
        assertTrue(t.debit)
        assertEquals("TALABAT", t.merchant)
        assertEquals("**1234", t.account)
        assertEquals("food", t.category)
    }

    @Test fun arabicDebitIgnoresBalance() {
        val t = ExpenseParser.parse("تم خصم مبلغ 1,500.00 جنيه من حسابك رقم ***5678 لدى كارفور يوم 17/11. الرصيد المتاح 10,000.00 جنيه")!!
        assertEquals(1500.0, t.amount, 0.001)
        assertTrue(t.debit)
        assertEquals("كارفور", t.merchant)
        assertEquals("groceries", t.category)
        assertEquals("**5678", t.account)
    }

    @Test fun incomingInstapay() {
        val t = ExpenseParser.parse("تم تحويل مبلغ ٣٠٠٠ ج.م إليك من أحمد محمد عبر InstaPay")!!
        assertEquals(3000.0, t.amount, 0.001)
        assertFalse(t.debit)
        assertEquals("income", t.category)
        assertEquals("أحمد محمد", t.merchant)
    }

    @Test fun walletTransferOut() {
        val t = ExpenseParser.parse("تم تحويل 200 جنيه إلى 01012345678 بنجاح. رصيدك 50 جنيه")!!
        assertEquals(200.0, t.amount, 0.001)
        assertTrue(t.debit)
        assertEquals("transfers", t.category)
    }

    @Test fun atm() {
        val t = ExpenseParser.parse("ATM withdrawal EGP 1000 from card ending 4321")!!
        assertTrue(t.debit)
        assertEquals(1000.0, t.amount, 0.001)
        assertEquals("cash", t.category)
    }

    @Test fun ignoresOtpAndPromos() {
        assertNull(ExpenseParser.parse("Your OTP is 123456. Do not share it."))
        assertNull(ExpenseParser.parse("خصم 20% على كل المنتجات لفترة محدودة!"))
        assertNull(ExpenseParser.parse("رمز التحقق الخاص بك هو 4455"))
    }
}

package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.feature.api.UserEditDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 个人资料编辑的**纯逻辑**测试（不联网、不碰 Android）。
 *
 * 重点锁住三件事：
 * 1. `GET /useredit` → 表单的**回显**（这正官方 App 修不好的那个 bug）；
 * 2. 表单 → `POST /useredit` 的 24 个字段**一个都不能漏**（整条覆盖）；
 * 3. 改密码的本地校验与输入上限。
 */
class ProfileEditLogicTest {

    // ---------------- 回显 ----------------

    @Test
    fun `读取到的资料完整映射到表单（每个字段都回显）`() {
        val dto = UserEditDto(
            nickName = "小早川",
            firstName = "悠",
            lastName = "小早川",
            gender = "Female",
            birthday = "1999-05-20",
            relations = "Open",
            sexuality = "Guys",
            website = "https://example.jp",
            birthPlace = "东京",
            city = "涩谷",
            country = "日本",
            occupation = "学生",
            company = "N/A",
            school = "都立高中",
            aboutMe = "关于我的一段话",
            infoHere = "这里的话",
            collections = "同人",
            ideal = "温柔的人",
            erogenic = "耳朵",
            favorite = "咖啡",
            hate = "噪音",
            status = "0",
        )
        val form = dto.toProfileForm()
        assertEquals("小早川", form.nickName)
        assertEquals("悠", form.firstName)
        assertEquals("小早川", form.lastName)
        assertEquals("Female", form.gender)
        assertEquals("1999-05-20", form.birthday)
        assertEquals("Open", form.relations)
        assertEquals("Guys", form.sexuality)
        assertEquals("https://example.jp", form.website)
        assertEquals("东京", form.birthPlace)
        assertEquals("涩谷", form.city)
        assertEquals("日本", form.country)
        assertEquals("学生", form.occupation)
        assertEquals("N/A", form.company)
        assertEquals("都立高中", form.school)
        assertEquals("关于我的一段话", form.aboutMe)
        assertEquals("这里的话", form.infoHere)
        assertEquals("同人", form.collections)
        assertEquals("温柔的人", form.ideal)
        assertEquals("耳朵", form.erogenic)
        assertEquals("咖啡", form.favorite)
        assertEquals("噪音", form.hate)
        assertEquals("0", form.status)
        // 密码本来就不回显（服务端返回空串），表单也必须是空
        assertEquals("", form.password)
        assertEquals("", form.passwordConfirm)
    }

    @Test
    fun `服务端的空生日归一值显示成未设置`() {
        // 实测：空值 / 0000-00-00 会被服务端归一成 1970-01-01，而且改不回去。
        // 三种形态都应该显示成"未设置"，而不是让用户以为自己生日是 1970 年。
        assertTrue(isUnsetBirthday(null))
        assertTrue(isUnsetBirthday(""))
        assertTrue(isUnsetBirthday("0000-00-00"))
        assertTrue(isUnsetBirthday("1970-01-01"))
        assertFalse(isUnsetBirthday("1999-05-20"))

        assertEquals("", UserEditDto(birthday = "0000-00-00").toProfileForm().birthday)
        assertEquals("", UserEditDto(birthday = "1970-01-01").toProfileForm().birthday)
        assertEquals("1999-05-20", UserEditDto(birthday = "1999-05-20").toProfileForm().birthday)
    }

    @Test
    fun `gender 为 null 时回显为空而不是崩`() {
        assertEquals("", UserEditDto(gender = null).toProfileForm().gender)
        assertEquals("0", UserEditDto(status = null).toProfileForm().status)
    }

    @Test
    fun `生日解析只认合法日期`() {
        assertEquals(LocalDate.of(1999, 5, 20), parseBirthday("1999-05-20"))
        assertNull(parseBirthday(""))
        assertNull(parseBirthday("1970-01-01"))
        assertNull(parseBirthday("1999/05/20"))
        assertEquals("1999-05-20", formatBirthday(LocalDate.of(1999, 5, 20)))
    }

    // ---------------- 全量提交 ----------------

    @Test
    fun `提交字段正好是官方的 24 项且顺序一致`() {
        val fields = UserProfileForm().toSubmitFields()
        assertEquals(PROFILE_EDIT_FIELDS, fields.map { it.first })
        assertEquals(24, fields.size)
        // 键不能重复：整条覆盖时重复键会让服务端只认其中一个
        assertEquals(fields.size, fields.map { it.first }.toSet().size)
    }

    @Test
    fun `只改昵称也会把其它字段原样回传`() {
        val form = UserProfileForm(
            nickName = "新昵称",
            firstName = "悠",
            birthday = "1999-05-20",
            aboutMe = "自我介绍",
        )
        val map = form.toSubmitFields().toMap()
        assertEquals("新昵称", map["nickName"])
        assertEquals("悠", map["firstName"])
        assertEquals("1999-05-20", map["birthday"])
        assertEquals("自我介绍", map["aboutMe"])
        // 没改的字段也要出现（否则服务端会把它们写成默认值）
        assertEquals("", map["lastName"])
        assertEquals("", map["website"])
        assertEquals("0", map["status"])
    }

    @Test
    fun `提交前去掉单行文本的首尾空白但保留密码原样`() {
        val form = UserProfileForm(
            nickName = "  昵称  ",
            city = " 东京 ",
            password = " p@ss ",
            passwordConfirm = " p@ss ",
            status = " ",
        )
        val map = form.toSubmitFields().toMap()
        assertEquals("昵称", map["nickName"])
        assertEquals("东京", map["city"])
        assertEquals(" p@ss ", map["password"])
        assertEquals(" p@ss ", map["password_confirm"])
        // status 被写成空白时回落到服务端的默认值
        assertEquals("0", map["status"])
    }

    // ---------------- 输入上限 ----------------

    @Test
    fun `单行与长文本分别截到 50 和 200 个码位`() {
        val form = UserProfileForm(
            nickName = "a".repeat(60),
            aboutMe = "b".repeat(250),
        ).limited()
        assertEquals(50, form.nickName.length)
        assertEquals(200, form.aboutMe.length)
        assertEquals(50, UserProfileForm(nickName = "c".repeat(60)).limited().nickName.length)
    }

    @Test
    fun `按码位截断不会把 emoji 劈成半个`() {
        val emoji = "😀"
        val form = UserProfileForm(nickName = emoji.repeat(60)).limited()
        // 50 个码位 = 50 个 emoji，而不是 25 个
        assertEquals(emoji.repeat(50), form.nickName)
        assertEquals(50, form.nickName.codePointCount(0, form.nickName.length))
    }

    // ---------------- 改密码校验 ----------------

    @Test
    fun `密码留空表示不修改`() {
        assertNull(validatePasswordChange(UserProfileForm()))
        assertNull(validatePasswordChange(UserProfileForm(password = "", passwordConfirm = "")))
    }

    @Test
    fun `改密码的三种非法输入都被本地拦住`() {
        assertEquals("请输入新密码", validatePasswordChange(UserProfileForm(passwordConfirm = "abc")))
        assertEquals("请再次输入新密码", validatePasswordChange(UserProfileForm(password = "abc")))
        assertEquals(
            "两次输入的新密码不一致",
            validatePasswordChange(UserProfileForm(password = "abc", passwordConfirm = "abd")),
        )
        assertNull(validatePasswordChange(UserProfileForm(password = "abc", passwordConfirm = "abc")))
    }

    @Test
    fun `下拉选项的展示值`() {
        assertEquals("未设置", optionLabel(PROFILE_RELATIONS, ""))
        assertEquals("单身", optionLabel(PROFILE_RELATIONS, "Single"))
        assertEquals("男 + 女", optionLabel(PROFILE_SEXUALITIES, "Guys + Girls"))
        // 服务端将来加了新选项也不该显示空白
        assertEquals("Unknown", optionLabel(PROFILE_RELATIONS, "Unknown"))
        assertEquals("未设置", optionLabel(PROFILE_GENDERS, ""))
    }
}

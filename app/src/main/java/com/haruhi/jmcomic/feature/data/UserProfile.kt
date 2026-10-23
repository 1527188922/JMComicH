package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.feature.api.UserEditDto
import java.time.LocalDate

/** 个人资料里**单行文本**的上限：官方编辑表单 `maxLength={50}`。 */
const val PROFILE_TEXT_MAX = 50

/** 个人资料里**长文本**（关于我等）的上限：官方 `maxLength={200}`。 */
const val PROFILE_LONG_TEXT_MAX = 200

/**
 * 个人资料编辑表单。
 *
 * 与 [UserEditDto] 一一对应（24 个字段），但把几个"服务端类型不稳定"的值统一成字符串：
 * `gender` 可能是 `null`；`birthday` 空值会被归一成 `1970-01-01`（见 [isUnsetBirthday]）。
 *
 * `password` / `passwordConfirm` 默认空 = **不修改密码**（官方语义：空密码字段不会改密码）。
 */
data class UserProfileForm(
    val nickName: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val gender: String = "",
    /** `yyyy-MM-dd`；空串 = 未设置（见 [isUnsetBirthday]）。 */
    val birthday: String = "",
    val relations: String = "",
    val sexuality: String = "",
    val website: String = "",
    val birthPlace: String = "",
    val city: String = "",
    val country: String = "",
    val occupation: String = "",
    val company: String = "",
    val school: String = "",
    val aboutMe: String = "",
    val infoHere: String = "",
    val collections: String = "",
    val ideal: String = "",
    val erogenic: String = "",
    val favorite: String = "",
    val hate: String = "",
    /** 服务端记录里的 `status`（实测是 `"0"`）；原样回传，不展示给用户。 */
    val status: String = "0",
) {
    /** 输入框的硬上限，按码位截断（emoji 不会被劈成半个）。 */
    fun limited(): UserProfileForm = copy(
        nickName = limitCodePoints(nickName, PROFILE_TEXT_MAX),
        firstName = limitCodePoints(firstName, PROFILE_TEXT_MAX),
        lastName = limitCodePoints(lastName, PROFILE_TEXT_MAX),
        website = limitCodePoints(website, PROFILE_TEXT_MAX),
        birthPlace = limitCodePoints(birthPlace, PROFILE_TEXT_MAX),
        city = limitCodePoints(city, PROFILE_TEXT_MAX),
        country = limitCodePoints(country, PROFILE_TEXT_MAX),
        occupation = limitCodePoints(occupation, PROFILE_TEXT_MAX),
        company = limitCodePoints(company, PROFILE_TEXT_MAX),
        school = limitCodePoints(school, PROFILE_TEXT_MAX),
        aboutMe = limitCodePoints(aboutMe, PROFILE_LONG_TEXT_MAX),
        infoHere = limitCodePoints(infoHere, PROFILE_LONG_TEXT_MAX),
        collections = limitCodePoints(collections, PROFILE_LONG_TEXT_MAX),
        ideal = limitCodePoints(ideal, PROFILE_LONG_TEXT_MAX),
        erogenic = limitCodePoints(erogenic, PROFILE_LONG_TEXT_MAX),
        favorite = limitCodePoints(favorite, PROFILE_LONG_TEXT_MAX),
        hate = limitCodePoints(hate, PROFILE_LONG_TEXT_MAX),
        password = limitCodePoints(password, PROFILE_TEXT_MAX),
        passwordConfirm = limitCodePoints(passwordConfirm, PROFILE_TEXT_MAX),
    )
}

/**
 * 官方 `POST /useredit/{uid}` 的 24 个字段名（顺序按 `GET /useredit` 的响应）。
 *
 * **整条覆盖**意味着每次保存都必须把这 24 项全部回传；这个表就是"没有漏项"的唯一依据，
 * 单测会核对 [UserProfileForm.toSubmitFields] 的键与顺序（顺序对服务端无意义，
 * 锁住它只是为了让抓包对比官方请求时少一处差异）。
 */
val PROFILE_EDIT_FIELDS: List<String> = listOf(
    "nickName",
    "password",
    "password_confirm",
    "firstName",
    "lastName",
    "gender",
    "birthday",
    "relations",
    "sexuality",
    "website",
    "birthPlace",
    "city",
    "country",
    "occupation",
    "company",
    "school",
    "aboutMe",
    "infoHere",
    "collections",
    "ideal",
    "erogenic",
    "favorite",
    "hate",
    "status",
)

/**
 * 服务端的"空生日"。
 *
 * 实测空值 / `0000-00-00` 会被归一成 `1970-01-01`，而且**改不回去**。这个日期不可能是
 * 用户真实选择的生日（日期选择器从 1900 起），所以这里把它也当成"未设置"：
 * 界面上显示「未设置」，而不是让用户以为自己填了个 1970 年的生日。
 */
private const val NORMALIZED_EMPTY_BIRTHDAY = "1970-01-01"

fun isUnsetBirthday(raw: String?): Boolean {
    val value = raw?.trim().orEmpty()
    return value.isEmpty() || value.startsWith("0000") || value == NORMALIZED_EMPTY_BIRTHDAY
}

/** `yyyy-MM-dd` → [LocalDate]；解析不了返回 null（不抛）。 */
fun parseBirthday(raw: String?): LocalDate? {
    val value = raw?.trim().orEmpty()
    if (isUnsetBirthday(value)) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}

/** [LocalDate] → 服务端要的 `yyyy-MM-dd`。 */
fun formatBirthday(date: LocalDate): String = date.toString()

/**
 * `GET /useredit/{uid}` → 表单。
 *
 * 唯一一处把服务端的类型不一致（`gender` 为 null、`birthday` 被归一成 1970-01-01）
 * 收口成"界面友好值"的地方。
 */
fun UserEditDto.toProfileForm(): UserProfileForm = UserProfileForm(
    nickName = nickName.orEmpty(),
    password = "",
    passwordConfirm = "",
    firstName = firstName.orEmpty(),
    lastName = lastName.orEmpty(),
    gender = gender.orEmpty(),
    birthday = if (isUnsetBirthday(birthday)) "" else birthday.orEmpty().trim(),
    relations = relations.orEmpty(),
    sexuality = sexuality.orEmpty(),
    website = website.orEmpty(),
    birthPlace = birthPlace.orEmpty(),
    city = city.orEmpty(),
    country = country.orEmpty(),
    occupation = occupation.orEmpty(),
    company = company.orEmpty(),
    school = school.orEmpty(),
    aboutMe = aboutMe.orEmpty(),
    infoHere = infoHere.orEmpty(),
    collections = collections.orEmpty(),
    ideal = ideal.orEmpty(),
    erogenic = erogenic.orEmpty(),
    favorite = favorite.orEmpty(),
    hate = hate.orEmpty(),
    status = status?.trim().orEmpty().ifBlank { "0" },
)

/**
 * 表单 → `POST /useredit/{uid}` 的 24 个字段（**全量**，顺序见 [PROFILE_EDIT_FIELDS]）。
 *
 * 只 trim 前后空白；`password` / `passwordConfirm` 保持原样（密码里的空格可能是有意的，
 * 但注册表单那边会拦首尾空格；这里是改密码，交给服务端判）。
 */
fun UserProfileForm.toSubmitFields(): List<Pair<String, String>> = listOf(
    "nickName" to nickName.trim(),
    "password" to password,
    "password_confirm" to passwordConfirm,
    "firstName" to firstName.trim(),
    "lastName" to lastName.trim(),
    "gender" to gender.trim(),
    // 未设置就交空串：服务端会归一成 1970-01-01（反正改不回去），由 [isUnsetBirthday] 负责再显示成"未设置"
    "birthday" to birthday.trim(),
    "relations" to relations.trim(),
    "sexuality" to sexuality.trim(),
    "website" to website.trim(),
    "birthPlace" to birthPlace.trim(),
    "city" to city.trim(),
    "country" to country.trim(),
    "occupation" to occupation.trim(),
    "company" to company.trim(),
    "school" to school.trim(),
    "aboutMe" to aboutMe.trim(),
    "infoHere" to infoHere.trim(),
    "collections" to collections.trim(),
    "ideal" to ideal.trim(),
    "erogenic" to erogenic.trim(),
    "favorite" to favorite.trim(),
    "hate" to hate.trim(),
    "status" to status.trim().ifBlank { "0" },
)

/**
 * 改密码的本地校验：通过返回 null。
 *
 * 规则（`docs/account-design.md` §7）：
 * - 两个都空 = 不改密码，放行；
 * - 只填一个 = 拦；
 * - 不一致 = 拦；
 * - 都填了 = 放行（长度由输入框上限保证，服务端还有自己的策略）。
 */
fun validatePasswordChange(form: UserProfileForm): String? {
    val password = form.password
    val confirm = form.passwordConfirm
    return when {
        password.isEmpty() && confirm.isEmpty() -> null
        password.isEmpty() -> "请输入新密码"
        confirm.isEmpty() -> "请再次输入新密码"
        password != confirm -> "两次输入的新密码不一致"
        else -> null
    }
}

/** 性别下拉的选项（`GET /useredit` 的取值）。 */
val PROFILE_GENDERS: List<Pair<String, String>> = listOf(
    "" to "未设置",
    "Male" to "男",
    "Female" to "女",
)

/** 关系下拉的选项（官方 `relations`）。 */
val PROFILE_RELATIONS: List<Pair<String, String>> = listOf(
    "" to "未设置",
    "Single" to "单身",
    "Taken" to "非单身",
    "Open" to "开放",
)

/** 兴趣下拉的选项（官方 `sexuality`）。 */
val PROFILE_SEXUALITIES: List<Pair<String, String>> = listOf(
    "" to "未设置",
    "Guys" to "男",
    "Girls" to "女",
    "Guys + Girls" to "男 + 女",
)

/** 下拉值的展示文案；未知值原样返回（服务端将来加新选项时不至于显示空白）。 */
fun optionLabel(options: List<Pair<String, String>>, value: String): String =
    options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "未设置" }

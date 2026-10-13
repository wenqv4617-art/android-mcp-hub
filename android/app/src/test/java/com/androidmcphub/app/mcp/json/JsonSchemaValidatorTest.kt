package com.androidmcphub.app.mcp.json

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsonSchemaValidator] 的测试：覆盖声明支持的每一个子集。
 *
 * 注意根值永远是**参数对象**（`McpTransport.callTool` 传进来的是 `JSONObject`），
 * 所以"某个值是什么类型"的用例都挂在 `v` 字段下，通过 [checkValue] 表达。
 */
class JsonSchemaValidatorTest {

    /** 直接校验整个参数对象。 */
    private fun validate(schema: String?, arguments: String): String? =
        JsonSchemaValidator.validate(schema?.let { JSONObject(it) }, JSONObject(arguments))

    /** 把 [valueJson] 放在 `v` 字段里，用 [valueSchema] 校验它。 */
    private fun checkValue(valueSchema: String, valueJson: String): String? =
        validate("""{"type":"object","properties":{"v":$valueSchema}}""", """{"v":$valueJson}""")

    // ---- type ----

    @Test
    fun `object 类型通过`() {
        assertNull(checkValue("""{"type":"object"}""", """{"a":1}"""))
        assertEquals("参数.v 的类型应为 object，实际是 integer", checkValue("""{"type":"object"}""", "1"))
    }

    @Test
    fun `type 为字符串时校验类型`() {
        assertNull(checkValue("""{"type":"string"}""", """"北京""""))
        assertEquals("参数.v 的类型应为 string，实际是 integer", checkValue("""{"type":"string"}""", "1"))
    }

    @Test
    fun `type 支持 number`() {
        assertNull(checkValue("""{"type":"number"}""", "1.5"))
        assertEquals("参数.v 的类型应为 number，实际是 string", checkValue("""{"type":"number"}""", """"x""""))
    }

    @Test
    fun `type 支持 integer 且拒绝小数`() {
        assertNull(checkValue("""{"type":"integer"}""", "3"))
        assertEquals("参数.v 的类型应为 integer，实际是 number", checkValue("""{"type":"integer"}""", "3.5"))
    }

    @Test
    fun `type 支持 boolean`() {
        assertNull(checkValue("""{"type":"boolean"}""", "true"))
        assertEquals("参数.v 的类型应为 boolean，实际是 string", checkValue("""{"type":"boolean"}""", """"true""""))
    }

    @Test
    fun `type 支持 array`() {
        assertNull(checkValue("""{"type":"array"}""", "[1,2]"))
        assertEquals("参数.v 的类型应为 array，实际是 object", checkValue("""{"type":"array"}""", """{"a":1}"""))
    }

    @Test
    fun `type 支持 null`() {
        assertNull(checkValue("""{"type":"null"}""", "null"))
        assertEquals("参数.v 的类型应为 null，实际是 integer", checkValue("""{"type":"null"}""", "1"))
    }

    @Test
    fun `type 为数组表示多选`() {
        assertNull(checkValue("""{"type":["string","null"]}""", "null"))
        assertNull(checkValue("""{"type":["string","null"]}""", """"x""""))
        assertEquals(
            "参数.v 的类型应为 string 或 null，实际是 integer",
            checkValue("""{"type":["string","null"]}""", "1"),
        )
    }

    @Test
    fun `不认识的 type 一律放行`() {
        assertNull(checkValue("""{"type":"date-time"}""", """"随便""""))
    }

    @Test
    fun `type 是空字符串时视为没有约束`() {
        assertNull(checkValue("""{"type":""}""", "1"))
    }

    @Test
    fun `type 不是字符串也不是数组时视为没有约束`() {
        assertNull(checkValue("""{"type":123}""", "1"))
    }

    // ---- properties / required ----

    @Test
    fun `缺少必填参数时报出字段名`() {
        val schema = """{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}"""
        assertEquals("缺少必填参数：city", validate(schema, """{}"""))
    }

    @Test
    fun `必填字段显式传 null 也算没给`() {
        assertEquals("缺少必填参数：city", validate("""{"type":"object","required":["city"]}""", """{"city":null}"""))
    }

    @Test
    fun `required 里的空串跳过`() {
        assertNull(validate("""{"type":"object","required":[""]}""", """{}"""))
    }

    @Test
    fun `属性类型不对时报错路径带上字段名`() {
        val schema = """{"type":"object","properties":{"limit":{"type":"integer"}}}"""
        assertEquals("参数.limit 的类型应为 integer，实际是 string", validate(schema, """{"limit":"many"}"""))
    }

    @Test
    fun `没在 properties 里的字段不会报错`() {
        val schema = """{"type":"object","properties":{"limit":{"type":"integer"}}}"""
        assertNull(validate(schema, """{"limit":1,"extra":"随便"}"""))
    }

    @Test
    fun `嵌套 object 递归校验`() {
        val schema =
            """{"type":"object","properties":{"point":{"type":"object","properties":{"lat":{"type":"number"}}}}}"""
        assertEquals("参数.point.lat 的类型应为 number，实际是 string", validate(schema, """{"point":{"lat":"north"}}"""))
        assertNull(validate(schema, """{"point":{"lat":1.5}}"""))
    }

    @Test
    fun `嵌套超过深度上限时报错而不是无限递归`() {
        // 根算第 0 层：a=1、b=2、c=3、d=4、e=5 —— 第 5 层才会超过上限 4。
        var schema = """{"type":"string"}"""
        for (name in listOf("e", "d", "c", "b", "a")) {
            schema = """{"type":"object","properties":{"$name":$schema}}"""
        }
        val problem = validate(schema, """{"a":{"b":{"c":{"d":{"e":"x"}}}}}""")
        assertTrue("深度超限要报错，实际：$problem", problem != null && problem.contains("嵌套层级超过上限"))
    }

    @Test
    fun `深度恰好在上限内时正常通过`() {
        var schema = """{"type":"string"}"""
        for (name in listOf("c", "b", "a")) {
            schema = """{"type":"object","properties":{"$name":$schema}}"""
        }
        assertNull(validate(schema, """{"a":{"b":{"c":"x"}}}"""))
    }

    // ---- additionalProperties ----

    @Test
    fun `additionalProperties 为 false 时多出的字段报错`() {
        val schema = """{"type":"object","properties":{"city":{"type":"string"}},"additionalProperties":false}"""
        assertEquals("参数 里出现了工具没声明的字段：extra", validate(schema, """{"city":"北京","extra":1}"""))
        assertNull(validate(schema, """{"city":"北京"}"""))
    }

    @Test
    fun `additionalProperties 为 true 或缺失时允许额外字段`() {
        assertNull(validate("""{"type":"object","additionalProperties":true}""", """{"extra":1}"""))
        assertNull(validate("""{"type":"object"}""", """{"extra":1}"""))
    }

    @Test
    fun `additionalProperties 为 false 且没有 properties 时所有字段都算多余`() {
        assertEquals("参数 里出现了工具没声明的字段：a", validate("""{"additionalProperties":false}""", """{"a":1}"""))
    }

    // ---- enum ----

    @Test
    fun `enum 命中的值通过`() {
        val schema = """{"type":"string","enum":["celsius","fahrenheit"]}"""
        assertNull(checkValue(schema, """"celsius""""))
    }

    @Test
    fun `enum 没命中的值报错`() {
        val schema = """{"type":"string","enum":["celsius","fahrenheit"]}"""
        assertEquals("参数.v 的值不在工具允许的范围内", checkValue(schema, """"kelvin""""))
    }

    @Test
    fun `enum 里的数字与文本不混淆`() {
        assertEquals("参数.v 的值不在工具允许的范围内", checkValue("""{"enum":[1,2]}""", """"1""""))
    }

    @Test
    fun `enum 里的整数与等值小数视为相等`() {
        assertNull(checkValue("""{"enum":[1]}""", "1.0"))
    }

    @Test
    fun `enum 里的 null 与 null 相等`() {
        assertNull(checkValue("""{"enum":[null]}""", "null"))
        assertEquals("参数.v 的值不在工具允许的范围内", checkValue("""{"enum":[null]}""", "1"))
    }

    // ---- 数值范围 / 字符串长度 ----

    @Test
    fun `minimum 与 maximum`() {
        val schema = """{"type":"number","minimum":1,"maximum":10}"""
        assertNull(checkValue(schema, "5"))
        assertEquals("参数.v 不能小于 1.0", checkValue(schema, "0.5"))
        assertEquals("参数.v 不能大于 10.0", checkValue(schema, "10.5"))
    }

    @Test
    fun `minLength 与 maxLength`() {
        val schema = """{"type":"string","minLength":3,"maxLength":5}"""
        assertNull(checkValue(schema, """"abcd""""))
        assertEquals("参数.v 至少需要 3 个字符", checkValue(schema, """"ab""""))
        assertEquals("参数.v 最多只能有 5 个字符", checkValue(schema, """"abcdef""""))
    }

    @Test
    fun `数值字段上的长度约束不生效`() {
        assertNull(checkValue("""{"type":"number","minLength":3}""", "1"))
    }

    @Test
    fun `最小最大值的写法不是数字时忽略该条约束`() {
        assertNull(checkValue("""{"type":"number","minimum":"一"}""", "0"))
        assertNull(checkValue("""{"type":"string","minLength":"三"}""", """"""""))
    }

    // ---- items ----

    @Test
    fun `数组的 items 只校验元素类型`() {
        val schema = """{"type":"array","items":{"type":"string"}}"""
        assertNull(checkValue(schema, """["a","b"]"""))
        assertEquals("参数.v[1] 的类型应为 string，实际是 integer", checkValue(schema, """["a",1]"""))
    }

    @Test
    fun `items 里的 null 元素也能识别`() {
        assertEquals("参数.v[0] 的类型应为 string，实际是 null", checkValue("""{"items":{"type":"string"}}""", "[null]"))
    }

    @Test
    fun `items 没有 type 时不做校验`() {
        assertNull(checkValue("""{"items":{"description":"随便"}}""", """[1,"a",null]"""))
    }

    @Test
    fun `items 不是对象时不做校验`() {
        assertNull(checkValue("""{"items":true}""", """[1,"a"]"""))
    }

    @Test
    fun `properties 里的数组字段与 items 一起工作`() {
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "tags",
                    JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                ),
            )
        assertNull(JsonSchemaValidator.validate(schema, JSONObject().put("tags", JSONArray().put("a"))))
        assertEquals(
            "参数.tags[0] 的类型应为 string，实际是 integer",
            JsonSchemaValidator.validate(schema, JSONObject().put("tags", JSONArray().put(1))),
        )
    }

    // ---- schema 缺失与参数大小 ----

    @Test
    fun `schema 为 null 时直接放行`() {
        assertNull(JsonSchemaValidator.validate(null, JSONObject("""{"随便":"什么都行"}""")))
    }

    @Test
    fun `schema 为 null 时参数超限依然要拦下来`() {
        val arguments = JSONObject().put("big", "x".repeat(JsonSchemaValidator.MAX_ARGUMENT_BYTES))
        val problem = JsonSchemaValidator.validate(null, arguments)
        assertTrue("该报参数太大，实际：$problem", problem != null && problem.contains("参数太大"))
    }

    @Test
    fun `参数整体超过 4096 字节时报错`() {
        val arguments = JSONObject().put("big", "x".repeat(5000))
        val problem = JsonSchemaValidator.validate(JSONObject("""{"type":"object"}"""), arguments)
        assertTrue("该报参数太大，实际：$problem", problem != null && problem.contains("参数太大"))
        assertTrue(problem!!.contains(JsonSchemaValidator.MAX_ARGUMENT_BYTES.toString()))
    }

    @Test
    fun `大小刚好在 4096 字节以内时通过`() {
        val filler = "x".repeat(4080)
        assertNull(validate("""{"type":"object"}""", JSONObject().put("a", filler).toString()))
    }
}

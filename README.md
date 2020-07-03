moshi-contrib
=============

This repository contains a single annotation processor which generates adapters for polymorphic types similar to [moshi-adapters][moshi-adapters]'s `PolymorphicJsonAdapterFactory` but at compile time.

  [moshi-adapters]: https://github.com/square/moshi/tree/master/adapters

<details>
<summary>Using <code>PolymorphicJsonAdapterFactory</code></summary>

```kotlin
sealed class Poly {
    data class Inner1(val value: String?) : Poly()
    data class Inner2(val value: String?) : Poly()
}

val moshi = Moshi.Builder()
    .add(PolymorphicJsonAdapterFactory.of(Poly::class.java, "type")
        .withSubtype(Poly.Inner1::class.java, "inner1")
        .withSubtype(Poly.Inner2::class.java, "inner2"))
    .add(KotlinJsonAdapterFactory())
    .build()
val adapter = moshi.adapter(Poly::class.java)
assert(adapter.fromJson("""{"type":"inner1","value":"hello"}""") == Poly.Inner1("hello"))
assert(adapter.toJson(Poly.Inner2("world")) == """{"type":"inner2","value":"world"}""")
```
</details> 

<details open>
<summary>With explicit subtypes and labels</summary>

```kotlin
@JsonClass(generateAdapter = true, generator = "com.github.ephemient.moshi.contrib.processor.JsonSubtypesCodeGenerator")
@JsonSubTypes(JsonSubTypes.Type(Poly.Inner1::class), JsonSubTypes.Type(Poly.Inner2::class))
sealed class Poly {
    @Json(name = "inner1")
    data class Inner1(val value: String?) : Poly()
    @Json(name = "inner2")
    data class Inner2(val value: String?) : Poly()
}

val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
val adapter = moshi.adapter(Poly::class.java)
assert(adapter.fromJson("""{"type":"inner1","value":"hello"}""") == Poly.Inner1("hello"))
assert(adapter.toJson(Poly.Inner2("world")) == """{"type":"inner2","value":"world"}""")
```
</details>

<details open>
<summary>Without explicit subtypes of labels</summary>

Note that all subtypes must be annotated with `@JsonClass` to be discovered, although `generateAdapter = true` is not required if you are using `KotlinJsonAdapterFactory`.
The processor will emit a warning if the base type is not `sealed`, `private`, or `internal`, as that may permit unprocessed subtypes added in other modules.

```kotlin
@JsonClass(generateAdapter = true, generator = "com.github.ephemient.moshi.contrib.processor.JsonSubtypesCodeGenerator")
sealed class Poly {
    @JsonClass(generateAdapter = true)
    data class Inner1(val value: String?) : Poly()
    @JsonClass(generateAdapter = true)
    data class Inner2(val value: String?) : Poly()
}

val moshi = Moshi.Builder().build()
val adapter = moshi.adapter(Poly::class.java)
assert(adapter.fromJson("""{"type":"Poly.Inner1","value":"hello"}""") == Poly.Inner1("hello"))
assert(adapter.toJson(Poly.Inner2("world")) == """{"type":"Poly.Inner2","value":"world"}""")
```
</details>

<details>
<summary>Custom label key</summary>

```kotlin
@JsonClass(generateAdapter = true, generator = "com.github.ephemient.moshi.contrib.processor.JsonSubtypesCodeGenerator")
@JsonSubTypes(JsonSubTypes.Type(Poly.Inner1::class, "inner1"), JsonSubTypes.Type(Poly.Inner2::class, "inner2"))
@JsonSubTypes.LabelKey("__typeinfo")
sealed class Poly {
    data class Inner1(val value: String?) : Poly()
    data class Inner2(val value: String?) : Poly()
}

val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
val adapter = moshi.adapter(Poly::class.java)
assert(adapter.fromJson("""{"__typeinfo":"inner1","value":"hello"}""") == Poly.Inner1("hello"))
assert(adapter.toJson(Poly.Inner2("world")) == """{"__typeinfo":"inner2","value":"world"}""")
```
</details>

<details>
<summary>Contextual label key</summary>

Install `<TYPE>JsonAdapter.Factory` to handle `@JsonSubTypes.LabelKey` at usage sites.
It can also override the default label key or change the default value handler.

```kotlin
@JsonClass(generateAdapter = true, generator = "com.github.ephemient.moshi.contrib.processor.JsonSubtypesCodeGenerator")
@JsonSubTypes(JsonSubTypes.Type(Poly.Inner1::class, "inner1"), JsonSubTypes.Type(Poly.Inner2::class, "inner2"))
sealed class Poly {
    @JsonClass(generateAdapter = true)
    data class Inner1(val value: String?) : Poly()
    @JsonClass(generateAdapter = true)
    data class Inner2(val value: String?) : Poly()
}
@JsonClass(generateAdapter = true)
data class Wrapper(
    @JsonSubTypes.LabelKey("kind") val data: Poly
)

val moshi = Moshi.Builder().add(PolyJsonAdapter.Factory()).build()
val adapter = moshi.adapter(Wrapper::class.java)
assert(adapter.fromJson("""{"data":{"kind":"inner1","value":"hello"}}""") == Wrapper(Poly.Inner1("hello")))
assert(adapter.toJson(Wrapper(Poly.Inner2("world"))) == """{"data":{"kind":"inner2","value":"world"}}""")
```
</details>

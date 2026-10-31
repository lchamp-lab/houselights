package app.houselights.ui.design

import java.lang.reflect.Field

/** The properties a token holder declares, in order: an instance's (`HlColors`) or an object's (`HlShapes`). */
fun propertyNames(type: Class<*>): List<String> = declared(type).map { it.name }

/** The properties of [type] that [owner] holds, by name, in order, so a check reads every token and lists none by hand. */
fun <T : Any> propertiesOf(owner: Any, type: Class<T>): Map<String, T> =
    declared(owner.javaClass).filter { type.isAssignableFrom(it.type) }.associate { field ->
        field.isAccessible = true
        field.name to checkNotNull(type.cast(field.get(owner)))
    }

/** What the class declares, without what Kotlin and Compose add (an object's `INSTANCE`, `$stable`). */
private fun declared(type: Class<*>): List<Field> =
    type.declaredFields.filter { !it.isSynthetic && '$' !in it.name && it.name != "INSTANCE" }

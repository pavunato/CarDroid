package android.annotation

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class SuppressLint(vararg val value: String)

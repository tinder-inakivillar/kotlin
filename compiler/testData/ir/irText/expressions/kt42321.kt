// SKIP_KT_DUMP

abstract class C<L> {
    fun takeT(x: L)
}

fun test() {
    val long = C<Long>()
    long.takeT(1.inv())
    long.takeT(1.unaryPlus())
    long.takeT(1.unaryMinus())
}

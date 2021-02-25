// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// This test exists only to check that we don't accidentally break the buggy behavior of the old backend in JVM IR (KT-38895).
// Feel free to remove it as soon as such code is prohibited in the compiler frontend (KT-38895).

// FILE: test.kt

class K<L> {
    fun check(x: L): Boolean = x is Int
}

fun box(): String {
    val k = K<Long>()
    if (!k.check(1.inv())) return "Fail K inv"
    if (!k.check(1.unaryPlus())) return "Fail K unaryPlus"
    if (!k.check(1.unaryMinus())) return "Fail K unaryMinus"

    val j = J<Long>()
    if (!j.check(1.inv())) return "Fail J inv"
    if (!j.check(1.unaryPlus())) return "Fail J unaryPlus"
    if (!j.check(1.unaryMinus())) return "Fail J unaryMinus"

    return "OK"
}

// FILE: J.java

public class J<M> {
    public boolean check(M x) {
        return x instanceof Integer;
    }
}

package foo

import test.A

fun callCompanionConstant() {
    println("Companion constant: ${A.COMPANION_VALUE}")
}

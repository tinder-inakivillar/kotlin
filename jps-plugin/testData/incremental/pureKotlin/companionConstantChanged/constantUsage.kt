package foo

import test.A.Companion.COMPANION_VALUE

fun callCompanionConstant() {
    println("Import companion constant: ${COMPANION_VALUE}")
}


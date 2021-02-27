sealed class SealedClass {
    class NestedInheritorA: SealedClass() {
        class NestedNestedInheritorA: SealedClass()
        object NestedNestedInheritorB: SealedClass()
    }
}

class ClassSameFileInheritorA: SealedClass()
class ClassSameFileInheritorB: SealedClass()
object ClassSameFileInheritorC: SealedClass()

sealed interface SealedInterface {
    class NestedInheritorA: SealedInterface {
        interface NestedNestedInheritorA: SealedInterface
        object NestedNestedInheritorB: SealedInterface
    }
}

class InterfaceSameFileInheritorA: SealedInterface
class InterfaceSameFileInheritorB: SealedInterface
object InterfaceSameFileInheritorC: SealedInterface

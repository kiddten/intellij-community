// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtNamedFunction
// OPTIONS: usages
package server

public open class Server() {
    open internal fun <caret>processRequest() = "foo"
}

public class ServerEx() : Server() {
    override fun processRequest() = "foofoo"
}



package example

class KotlinOuterTest {
    /* class Fake { /* nested comment */ @Test fun ignored() {} } */
    val deceptive = """
        class Fake { @Test fun ignored() {} }
    """
    class Marker(val label: String)
    @Test fun sameName() { val anonymous = object : Any() {} }

    class NestedTest {
        @Test @Config(sdk = [28, 35]) fun `nested works`() {}
    }
}

class KotlinSiblingTest {
    @Test fun sibling() {}
}

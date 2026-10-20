package example;

public class JavaOuterTest {
    @BeforeClass public static void readHost() { String input = "DOUAI_TEST_APK"; }
    // class Fake { @Test public void ignored() { } }
    private String deceptive = "class Fake { @Test public void ignored() { } }";
    @Test public void sameName() { char brace = '}'; }

    public static class NestedTest {
        @Test public void sameName() { }
        public static class DeepTest {
            @Test public void deep() { }
        }
    }

    @Test public void afterNestedClass() { }
}

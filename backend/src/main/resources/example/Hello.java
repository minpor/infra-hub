public class Hello {
    static String greeting(String name) { return "Hello, " + name + "!"; }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            if (!greeting("Infra Hub").equals("Hello, Infra Hub!")) {
                throw new AssertionError("Unexpected greeting");
            }
            System.out.println("PASS: greeting");
            // A small real workload also leaves time to exercise cancellation.
            for (int i = 1; i <= 5; i++) {
                Thread.sleep(1000);
                System.out.println("PASS: worker heartbeat checkpoint " + i);
            }
        } else {
            System.out.println(greeting("Infra Hub"));
        }
    }
}

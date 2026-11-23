// Prints every game class an active module patches (used by the agent smoke test).
import java.util.*;
import splint.core.module.SplintModule;

public class ListTargets {
    public static void main(String[] a) {
        Set<String> out = new TreeSet<>();
        for (SplintModule m : ServiceLoader.load(SplintModule.class)) {
            m.patches().forEach(p -> out.add(p.owner()));
            m.redirects().forEach(r -> out.add(r.owner()));
            m.fields().forEach(f -> out.add(f.owner()));
            m.callSites().forEach(c -> out.add(c.owner()));
        }
        out.forEach(System.out::println);
    }
}

// Boots the game's own JVM with the agent and loads every patch target, the way the game would,
// then asks the agent's PatchRegistry whether everything applied. Catches what the offline check
// cannot: a class loaded during premain (for example by the JVM verifier while a module is
// constructed) runs unpatched for the whole session. Usage: python3 build.py --verify.
public class AgentSmoke {
    public static void main(String[] a) throws Exception {
        // Load and link (which runs the verifier on the patched code) without running static
        // initialisers: some, such as ServerMap's, need a started game.
        for (String name : a) Class.forName(name.replace('/', '.'), false, AgentSmoke.class.getClassLoader()).getDeclaredMethods();
        Class<?> registry = Class.forName("splint.core.patch.PatchRegistry");
        String summary = (String) registry.getMethod("summary").invoke(null);
        int applied = (int) registry.getMethod("applied").invoke(null);
        int total = (int) registry.getMethod("total").invoke(null);
        System.out.println("agent smoke: " + summary);
        System.exit(applied == total && summary.contains("none missing") ? 0 : 1);
    }
}

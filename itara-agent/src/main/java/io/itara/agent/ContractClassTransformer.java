package io.itara.agent;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import io.itara.api.ComponentInterface;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Installs a class file transformer that ensures every @ComponentInterface
 * abstract class has a no-arg constructor.
 *
 * ByteBuddy's generated proxy subclasses need to call super() with no
 * arguments. If the contract author didn't provide a no-arg constructor,
 * proxy generation fails. Rather than forcing the developer to remember
 * to add one, the agent patches it in transparently as the class loads.
 *
 * Uses a raw ClassFileTransformer via ASM directly, which avoids
 * version-sensitive generic signatures in ByteBuddy's AsmVisitorWrapper API.
 */
public class ContractClassTransformer {

    /**
     * Installs the transformer into the JVM instrumentation pipeline.
     * Must be called before any @ComponentInterface class is loaded.
     */
    public static void install(Instrumentation instrumentation) {
        instrumentation.addTransformer(new NoArgConstructorTransformer(), true);
        System.out.println("[Itara] ContractClassTransformer installed.");
    }

    /**
     * Raw ClassFileTransformer that patches a no-arg constructor into
     * any class annotated with @ComponentInterface that lacks one.
     *
     * Runs for every class the JVM loads. The check is fast — we bail
     * out immediately for anything that doesn't have the annotation.
     */
    static class NoArgConstructorTransformer implements ClassFileTransformer {

        // The descriptor of @ComponentInterface in ASM's internal format
        // e.g. "LItara/api/ComponentInterface;"
        private static final String COMPONENT_INTERFACE_DESC =
                "L" + ComponentInterface.class.getName().replace('.', '/') + ";";

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {

            // Skip JDK, Itara internals, and anything without a class name
            if (className == null
                    || className.startsWith("java/")
                    || className.startsWith("javax/")
                    || className.startsWith("sun/")
                    || className.startsWith("jdk/")
                    || className.startsWith("Itara/")) {
                return null; // null = no transformation
            }

            try {
                // Use ASM to inspect and optionally patch the class
                return maybeAddNoArgConstructor(classfileBuffer);
            } catch (Throwable t) {
                // Never crash the JVM — if we can't transform, leave it alone
                return null;
            }
        }

        private byte[] maybeAddNoArgConstructor(byte[] original) {
            // First pass: check if this class has @ComponentInterface
            // and whether it already has a no-arg constructor
            InspectionVisitor inspector = new InspectionVisitor();
            new net.bytebuddy.jar.asm.ClassReader(original).accept(
                    inspector,
                    net.bytebuddy.jar.asm.ClassReader.SKIP_CODE);

            if (!inspector.hasComponentInterfaceAnnotation) {
                return null; // not a contract — skip
            }
            if (inspector.hasNoArgConstructor) {
                System.out.println("[Itara] Contract " + inspector.className
                        + " already has no-arg constructor — no patch needed.");
                return null; // already fine — skip
            }

            // Second pass: rewrite the class adding the no-arg constructor
            System.out.println("[Itara] Patching no-arg constructor into: "
                    + inspector.className);

            net.bytebuddy.jar.asm.ClassReader reader =
                    new net.bytebuddy.jar.asm.ClassReader(original);
            ClassWriter writer = new ClassWriter(
                    reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            reader.accept(new AddConstructorVisitor(writer, inspector.superName), 0);
            return writer.toByteArray();
        }
    }

    /**
     * First-pass visitor: inspects the class without modifying it.
     * Checks for @ComponentInterface annotation and no-arg constructor presence.
     */
    static class InspectionVisitor extends ClassVisitor {

        boolean hasComponentInterfaceAnnotation = false;
        boolean hasNoArgConstructor = false;
        String className = "";
        String superName = "java/lang/Object";

        InspectionVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
            this.superName = superName != null ? superName : "java/lang/Object";
        }

        @Override
        public net.bytebuddy.jar.asm.AnnotationVisitor visitAnnotation(
                String descriptor, boolean visible) {
            if (NoArgConstructorTransformer.COMPONENT_INTERFACE_DESC.equals(descriptor)) {
                hasComponentInterfaceAnnotation = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name,
                                         String descriptor, String signature,
                                         String[] exceptions) {
            if ("<init>".equals(name) && "()V".equals(descriptor)) {
                hasNoArgConstructor = true;
            }
            return null;
        }
    }

    /**
     * Second-pass visitor: copies the class and appends a no-arg constructor.
     *
     * Generated constructor is equivalent to:
     *   protected MyClass() { super(); }
     */
    static class AddConstructorVisitor extends ClassVisitor {

        private final String superName;

        AddConstructorVisitor(ClassVisitor cv, String superName) {
            super(Opcodes.ASM9, cv);
            this.superName = superName;
        }

        @Override
        public void visitEnd() {
            // Emit protected no-arg constructor before closing the class
            MethodVisitor mv = cv.visitMethod(
                    Opcodes.ACC_PROTECTED,
                    "<init>",
                    "()V",
                    null,
                    null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);          // push 'this'
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    superName,
                    "<init>",
                    "()V",
                    false);                              // call super()
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();

            super.visitEnd();
        }
    }
}

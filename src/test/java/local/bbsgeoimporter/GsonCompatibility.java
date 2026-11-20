package local.bbsgeoimporter;
import org.objectweb.asm.*;
import java.util.jar.*;
import java.util.*;
public final class GsonCompatibility {
    static int references;
    static boolean method(Class<?> c,String name,String desc){
        for(var m:c.getDeclaredMethods())if(m.getName().equals(name)&&Type.getMethodDescriptor(m).equals(desc))return true;
        if(c.getSuperclass()!=null&&method(c.getSuperclass(),name,desc))return true;
        for(var i:c.getInterfaces())if(method(i,name,desc))return true;
        return false;
    }
    public static void main(String[] args)throws Exception{
        String location=com.google.gson.JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if(!location.endsWith("/2.10/gson-2.10.jar"))throw new AssertionError("Expected game Gson 2.10: "+location);
        try(var jar=new JarFile(args[0])){
            for(var entry:Collections.list(jar.entries()))if(entry.getName().endsWith(".class")){
                new ClassReader(jar.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM9){
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){return new MethodVisitor(Opcodes.ASM9){
                        public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                            if(!owner.startsWith("com/google/gson/"))return;
                            try{
                                Class<?> c=Class.forName(owner.replace('/','.'));boolean found;
                                if(name.equals("<init>"))found=Arrays.stream(c.getDeclaredConstructors()).anyMatch(k->Type.getConstructorDescriptor(k).equals(descriptor));
                                else found=method(c,name,descriptor);
                                if(!found)throw new AssertionError("Missing Gson method: "+owner+"."+name+descriptor+" in "+entry.getName());
                                references++;
                            }catch(ClassNotFoundException e){throw new AssertionError(e);}
                        }
                    };}
                },0);
            }
        }
        if(references==0)throw new AssertionError("No references inspected");
        System.out.println("GSON 2.10 ARTIFACT PASS: "+references+" method references; "+location);
    }
}

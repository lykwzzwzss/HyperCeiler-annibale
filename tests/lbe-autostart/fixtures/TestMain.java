import java.lang.reflect.*;
import java.util.*;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.lbe.AutoStart;
public class TestMain {
 static final Map<Method,XposedInterface.Hooker> hooks=new LinkedHashMap<>();
 static int passes,failures,originalCalls,revoked,restored,marked,legacyCalls;
 static final String JOB="com.hyperos.security.service.OnePushJobService$GetCloudDataTask";
 static final String PROVIDER="com.hyperos.security.service.provider.PermissionManagerProvider";
 static final String REPO="com.hyperos.permissiondatabase.PackagePermissionRepository";
 static final String MANAGER="com.miui.permission.PermissionManager";
 static final String LEGACY="com.miui.privacy.autostart.AutoRevokePermissionManager";
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 interface Test{void run()throws Throwable;}
 static void test(String name,Test run){reset();try{run.run();passes++;System.out.println("PASS "+name);}catch(Throwable error){failures++;System.out.println("FAIL "+name+": "+error);error.printStackTrace(System.out);}}
 static void reset(){hooks.clear();BaseHook.classes.clear();BaseHook.errors.clear();BaseHook.installs=BaseHook.failAt=0;BaseHook.throwInstall=false;originalCalls=revoked=restored=marked=legacyCalls=0;Manager.PERM_ID_AUTOSTART=16384;BaseHook.classes.putAll(Map.of(JOB,Job.class,PROVIDER,Provider.class,REPO,Repository.class,MANAGER,Manager.class));}
 static void start(){new AutoStart().init();}
 static Object call(Class<?> owner,String name,Class<?>[] types,Object target,Object...args)throws Throwable{Method m=owner.getDeclaredMethod(name,types);m.setAccessible(true);Chain c=new Chain(m,target,args);return hooks.containsKey(m)?hooks.get(m).intercept(c):c.proceed();}
 static boolean query(long permission)throws Throwable{return (Boolean)call(Repository.class,"isUserChangePerm",new Class<?>[]{String.class,int.class,long.class},new Repository(),"test.app",0,permission);}
 static Bundle deal(Bundle args)throws Throwable{return (Bundle)call(Provider.class,"dealOnePush",new Class<?>[]{Bundle.class},new Provider(),args);}
 public static class Job{private void revokeAutoStart(Context c,PackageInfo p,List<?> list){revoked++;marked++;}}
 public static class Manager{public static long PERM_ID_AUTOSTART=16384;public void manualDisable(){revoked++;}}
 public static class Repository{public final boolean isUserChangePerm(String p,int u,long permission){return false;}}
 public static class Provider{private Bundle dealOnePush(Bundle args)throws Throwable{boolean user=query(args.permission);if(args.during!=null)args.during.run();if(args.nest)deal(new Bundle());if(args.explode)throw new IllegalStateException("original failed");if(args.restore)restored++;else if(args.registration&&!user){revoked++;marked++;}return args;}}
 public static class Legacy{private static void lambda$startScheduleASCheck$1(Context c,boolean enabled){legacyCalls++;}}
 public static class BadRepository{public final int isUserChangePerm(String p,int u,long permission){return 0;}}
 public static class BadId{public static String PERM_ID_AUTOSTART="16384";}
 static final class Chain implements XposedInterface.Chain{final Method method;final Object target;final Object[]args;Chain(Method m,Object t,Object[]a){method=m;target=t;args=a;}public Executable getExecutable(){return method;}public Object getThisObject(){return target;}public List<Object>getArgs(){return Arrays.asList(args);}public Object getArg(int i){return args[i];}public Object proceed()throws Throwable{return proceedWith(target,args);}public Object proceed(Object[]a)throws Throwable{return proceedWith(target,a);}public Object proceedWith(Object t)throws Throwable{return proceedWith(t,args);}public Object proceedWith(Object t,Object[]a)throws Throwable{originalCalls++;try{return method.invoke(t,a);}catch(InvocationTargetException e){throw e.getCause();}}}
 static Object proxy(Class<?>t,InvocationHandler h){return Proxy.newProxyInstance(TestMain.class.getClassLoader(),new Class<?>[]{t},h);}
 static void setup(){XposedInterface api=(XposedInterface)proxy(XposedInterface.class,(p,m,a)->{if(m.getName().equals("hook")){Method target=(Method)a[0];return proxy(XposedInterface.HookBuilder.class,(b,bm,ba)->{if(bm.getName().equals("intercept")){check(!hooks.containsKey(target));hooks.put(target,(XposedInterface.Hooker)ba[0]);return proxy(XposedInterface.HookHandle.class,(h,hm,ha)->{if(hm.getName().equals("unhook"))hooks.remove(target);if(hm.getName().equals("getExecutable"))return target;return null;});}return b;});}if(m.getReturnType()==int.class)return 102;if(m.getReturnType()==long.class)return 0L;if(m.getReturnType()==boolean.class)return false;if(m.getReturnType()==String.class)return "host-api102";return null;});EzXposed.setBase$hook_xposed_102(api);EzXposed.setHotReloadEnabled(false);}

 public static void main(String[] args){setup();
  test("legacy_contract_still_supported",()->{BaseHook.classes.clear();BaseHook.classes.put(LEGACY,Legacy.class);start();check(hooks.size()==1);call(Legacy.class,"lambda$startScheduleASCheck$1",new Class<?>[]{Context.class,boolean.class},null,new Context(),true);check(legacyCalls==0);});
  test("modern_resolves_all_three_hooks",()->{start();check(hooks.size()==3);});
  test("scheduled_revoke_has_no_permission_or_bookkeeping_side_effect",()->{start();call(Job.class,"revokeAutoStart",new Class<?>[]{Context.class,PackageInfo.class,List.class},new Job(),new Context(),new PackageInfo(),List.of());check(revoked==0&&marked==0);});
  test("onepush_registration_is_protected_without_marking_revoked",()->{start();Bundle b=new Bundle();check(deal(b)==b&&revoked==0&&marked==0);check(!query(16384));});
  test("deregistration_restoration_is_preserved",()->{start();Bundle b=new Bundle();b.restore=true;deal(b);check(restored==1&&revoked==0&&marked==0);});
  test("other_permission_queries_are_unchanged_inside_provider",()->{start();Bundle b=new Bundle();b.permission=32;deal(b);check(revoked==1&&marked==1);});
  test("manual_disable_is_unchanged",()->{start();new Manager().manualDisable();check(revoked==1&&!query(16384));});
  test("scope_is_cleaned_after_original_exception",()->{start();Bundle b=new Bundle();b.explode=true;try{deal(b);throw new AssertionError();}catch(IllegalStateException expected){}check(!query(16384));});
  test("nested_provider_scopes_are_balanced",()->{start();Bundle b=new Bundle();b.nest=true;deal(b);check(revoked==0&&marked==0&&!query(16384));});
  test("scope_does_not_escape_to_other_threads",()->{start();boolean[] changed={true};Bundle b=new Bundle();b.during=()->{Thread t=new Thread(()->{try{changed[0]=query(16384);}catch(Throwable e){throw new AssertionError(e);}});t.start();try{t.join(3000);}catch(InterruptedException e){throw new AssertionError(e);}check(!t.isAlive());};deal(b);check(!changed[0]&&revoked==0&&!query(16384));});
  test("repeated_calls_do_not_leave_scope_or_persistent_flags",()->{start();for(int i=0;i<30;i++){deal(new Bundle());check(!query(16384));}check(revoked==0&&marked==0);});
  test("runtime_permission_id_is_used",()->{Manager.PERM_ID_AUTOSTART=777;start();Bundle b=new Bundle();b.permission=777;deal(b);check(revoked==0);b.permission=16384;deal(b);check(revoked==1);});
  for(String type:List.of(JOB,PROVIDER,REPO,MANAGER))test("missing_contract_"+type,()->{BaseHook.classes.remove(type);try{start();throw new AssertionError();}catch(IllegalStateException expected){check(hooks.isEmpty());}});
  test("wrong_return_type_rejected_before_hooking",()->{BaseHook.classes.put(REPO,BadRepository.class);try{start();throw new AssertionError();}catch(IllegalStateException expected){check(hooks.isEmpty());}});
  test("wrong_permission_field_type_rejected_before_hooking",()->{BaseHook.classes.put(MANAGER,BadId.class);try{start();throw new AssertionError();}catch(IllegalStateException expected){check(hooks.isEmpty());}});
  test("invalid_permission_id_rejected",()->{Manager.PERM_ID_AUTOSTART=0;try{start();throw new AssertionError();}catch(IllegalStateException expected){check(hooks.isEmpty());}});
  for(int fail=1;fail<=3;fail++)for(boolean throwing:new boolean[]{false,true}){int at=fail;test("partial_registration_rollback_"+at+"_"+throwing,()->{BaseHook.failAt=at;BaseHook.throwInstall=throwing;try{start();throw new AssertionError();}catch(IllegalStateException expected){check(hooks.isEmpty());}});}
  System.out.println("RESULT passes="+passes+" failures="+failures);if(failures>0)System.exit(1);
 }
}

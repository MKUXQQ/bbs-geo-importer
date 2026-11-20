package local.bbsgeoimporter;

import java.util.concurrent.*;
import java.util.function.Function;

/** Shared by the actual Forge and Fabric listeners, including their preparation barrier. */
public final class StartupReload {
    private StartupReload(){}
    public static CompletableFuture<Void> await(ScanService service,EffectiveResources.Reader reader,
            Executor prepare,Function<ReloadSnapshot,CompletableFuture<ReloadSnapshot>> barrier,Executor apply){
        return service.prepare(reader,prepare).thenCompose(barrier).thenCompose(value->service.applyAndAwait(value,apply));
    }
}

package at.redi2go.photonics.client;

import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.systems.Ph_GlGpuDevice;
import com.vdurmont.semver4j.Semver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;

public class PhotonicsClientFabric implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Photonics");

    @Override
    public void onInitializeClient() {
        Optional<ModContainer> photonics = FabricLoader.getInstance().getModContainer("photonics");
        if (photonics.isEmpty()) throw new IllegalStateException("Where is photonics? :(");

        boolean isDevEnv = FabricLoader.getInstance().isDevelopmentEnvironment();

        try {
            Photonics.init(
                    new Semver(photonics.get().getMetadata().getVersion().getFriendlyString()),
                    isDevEnv,
                    isDevEnv ? photonics.get().getRootPaths().getFirst().resolve("assets") : Path.of(Photonics.class.getResource("/assets").toURI())
            );
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException | Error e) {
            LOGGER.error("Photonics.init failed during Fabric client initialization", e);
            throw e;
        }

        // Create the 1.21.1 GPU device after Minecraft has a current GL context.
        Minecraft.getInstance().execute(Ph_GlGpuDevice::init);
    }
}

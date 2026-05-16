package at.redi2go.photonics.impl.mixins.mc.core.registries;

import at.redi2go.photonics.api.mc.core.IHolderLookup;
import at.redi2go.photonics.api.mc.core.registries.Registries;
import at.redi2go.photonics.api.mc.world.level.IBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Registries.class)
@SuppressWarnings("unchecked")
public interface RegistriesImpl {
    @Overwrite
    static IHolderLookup<IBlock> block() {
        // 1.21.1: Registry<T> does not extend HolderLookup; obtain the HolderLookup view
        // via Registry#asLookup() (returns HolderLookup.RegistryLookup<T>, which
        // extends HolderLookup<T> and is what HolderLookupMixin actually targets).
        return (IHolderLookup<IBlock>) BuiltInRegistries.BLOCK.asLookup();
    }
}

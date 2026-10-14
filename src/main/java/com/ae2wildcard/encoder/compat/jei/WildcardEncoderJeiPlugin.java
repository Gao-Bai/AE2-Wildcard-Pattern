package com.ae2wildcard.encoder.compat.jei;

import java.util.List;

import com.ae2wildcard.encoder.client.WildcardPatternScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI integration: drag an ingredient out of JEI and drop it on the rule list.
 *
 * <p>JEI is an <b>optional</b> dependency. This class lives in its own package and is only ever loaded
 * by JEI itself (through the {@code @JeiPlugin} annotation), so the mod works fine without JEI
 * installed - nothing else in the mod references any {@code mezz.jei} type.
 */
@JeiPlugin
public class WildcardEncoderJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation("wildcardencoder", "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(WildcardPatternScreen.class, new WildcardGhostHandler());
    }

    /** Offers the rule list as a drop target for item ingredients dragged out of JEI. */
    private static final class WildcardGhostHandler implements IGhostIngredientHandler<WildcardPatternScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(WildcardPatternScreen screen, ITypedIngredient<I> ingredient,
                boolean doStart) {
            if (!(ingredient.getIngredient() instanceof ItemStack)) {
                return List.of();
            }
            return List.of(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return screen.listArea();
                }

                @Override
                public void accept(I value) {
                    if (value instanceof ItemStack stack) {
                        screen.addFromStack(stack);
                    }
                }
            });
        }

        @Override
        public void onComplete() {
            // nothing to clean up: every accepted ingredient is applied immediately
        }
    }
}

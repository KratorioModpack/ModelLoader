package com.cout970.modelloader

import net.minecraftforge.fml.common.FMLModContainer
import net.minecraftforge.fml.common.ILanguageAdapter
import net.minecraftforge.fml.common.ModContainer
import net.minecraftforge.fml.relauncher.Side
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * This class allow to load the Mod class into forge, this is needed because the
 * mod class is an object (Singleton)
 */
@Suppress("unused")
class KotlinAdapter : ILanguageAdapter {

    override fun supportsStatics() = false

    override fun setProxy(target: Field?, proxyTarget: Class<*>?, proxy: Any?) {
        target?.set(null, proxy)
    }

    override fun getNewInstance(mod: FMLModContainer?, modClass: Class<*>?, loader: ClassLoader?,
                                factory: Method?): Any? {
        return modClass?.getField("INSTANCE")?.get(null)
    }

    override fun setInternalProxies(mod: ModContainer?, side: Side?, loader: ClassLoader?) = Unit
}
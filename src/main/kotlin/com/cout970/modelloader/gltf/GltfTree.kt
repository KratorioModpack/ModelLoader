package com.cout970.modelloader.gltf

import com.cout970.modelloader.ModelLoaderMod
import com.cout970.modelloader.api.TRSTransformation
import net.minecraft.util.ResourceLocation
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.vecmath.Quat4d
import javax.vecmath.Vector2d
import javax.vecmath.Vector3d
import javax.vecmath.Vector4d

object GltfTree {

    fun parse(file: GltfFile, location: ResourceLocation, folder: (String) -> InputStream): DefinitionTree {
        val buffers = parseBuffers(file, folder)
        val bufferViews = parseBufferViews(file, buffers)
        val accessors = parseAccessors(file, bufferViews)
        val textures = mutableSetOf<ResourceLocation>()
        val meshes = parseMeshes(file, accessors, location, textures)
        val scenes = parseScenes(file, meshes)
        val animations = parseAnimations(file, accessors)

        return DefinitionTree(file.scene ?: 0, scenes, animations, textures, file.extras)
    }

    private fun parseBuffers(file: GltfFile, folder: (String) -> InputStream): List<ByteArray> {
        return file.buffers.map { buff ->

            val uri = buff.uri ?: error("Found buffer without uri, unable to load, buffer: $buff")
            val bytes = folder(uri).readBytes()

            if (bytes.size != buff.byteLength) {
                error("Buffer byteLength, and resource size doesn't match, buffer: $buff, resource size: ${bytes.size}")
            }

            bytes
        }
    }

    private fun parseBufferViews(file: GltfFile, buffers: List<ByteArray>): List<ByteArray> {
        return file.bufferViews.map { view ->

            val buffer = buffers[view.buffer]
            val offset = view.byteOffset ?: 0
            val size = view.byteLength

            buffer.copyOfRange(offset, offset + size)
        }
    }

    private fun parseAccessors(file: GltfFile, bufferViews: List<ByteArray>): List<Buffer> {
        return file.accessors.map { accessor ->

            val viewIndex = accessor.bufferView ?: error("Unsupported Empty BufferView at accessor: $accessor")

            val buffer = bufferViews[viewIndex]

            val offset = accessor.byteOffset ?: 0
            val type = GltfComponentType.fromId(accessor.componentType)

            val buff = ByteBuffer.wrap(buffer, offset, buffer.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            val list: List<Any> = intoList(accessor.type, type, accessor.count, buff)

            Buffer(accessor.type, type, list)
        }
    }

    @Suppress("UnnecessaryVariable")
    private fun intoList(listType: GltfType, componentType: GltfComponentType, count: Int, buffer: ByteBuffer): List<Any> {
        val t = componentType
        val b = buffer
        return when (listType) {
            GltfType.SCALAR -> List(count) { b.next(t) }
            GltfType.VEC2 -> List(count) { Vector2d(b.next(t).toDouble(), b.next(t).toDouble()) }
            GltfType.VEC3 -> List(count) { Vector3d(b.next(t).toDouble(), b.next(t).toDouble(), b.next(t).toDouble()) }
            GltfType.VEC4 -> List(count) { Vector4d(b.next(t).toDouble(), b.next(t).toDouble(), b.next(t).toDouble(), b.next(t).toDouble()) }
            GltfType.MAT2 -> error("Unsupported")
            GltfType.MAT3 -> error("Unsupported")
            GltfType.MAT4 -> error("Unsupported")
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun ByteBuffer.next(type: GltfComponentType): Number {
        return when (type) {
            GltfComponentType.BYTE, GltfComponentType.UNSIGNED_BYTE -> get()
            GltfComponentType.SHORT, GltfComponentType.UNSIGNED_SHORT -> short
            GltfComponentType.UNSIGNED_INT -> int
            GltfComponentType.FLOAT -> float
        }
    }

    private fun parseMeshes(file: GltfFile, accessors: List<Buffer>,
                            location: ResourceLocation, textures: MutableSet<ResourceLocation>): List<Mesh> {
        return file.meshes.map { mesh ->
            val primitives = mesh.primitives.map { prim ->

                val attr = prim.attributes.map { (k, v) ->
                    Pair(GltfAttribute.valueOf(k), accessors[v])
                }.toMap()

                val indices = prim.indices?.let { accessors[it] }
                val mode = GltfMode.fromId(prim.mode)

                val material = getMaterial(file, prim.material, location) ?: ModelLoaderMod.defaultModelTexture
                textures += material

                Primitive(attr, indices, mode, material)
            }

            Mesh(primitives)
        }
    }

    private fun getMaterial(file: GltfFile, mat: Int?, location: ResourceLocation): ResourceLocation? {
        if (mat == null) return null
        val material = file.materials[mat]
        val texture = material.pbrMetallicRoughness?.baseColorTexture?.index ?: return null
        val image = file.textures[texture].source ?: return null
        val texturePath = file.images[image].uri ?: return null

        // If the texture path is a resource location, no extra processing is done
        if (texturePath.contains(':')) {
            return ResourceLocation(texturePath)
        }

        val relativeModelPath = location.path.substringAfter("models/")
        val localPath = relativeModelPath.substringBeforeLast('/', "")

        // TexturePath: windmill.png
        // Location: modid:models/block/gltf/windmill.gltf
        // Result: modid:textures/block/gltf/windmill

        val finalPath = buildString {
            if (localPath.isNotEmpty()) {
                append(localPath)
                append("/")
            }
            append(texturePath.substringBeforeLast('.'))
        }

        return ResourceLocation(location.namespace, finalPath)
    }

    private fun parseScenes(file: GltfFile, meshes: List<Mesh>): List<Scene> {
        return file.scenes.map { scene ->
            val nodes = scene.nodes ?: emptyList()
            val parsedNodes = nodes.map { parseNode(file, it, file.nodes[it], meshes) }

            Scene(parsedNodes)
        }
    }

    private fun parseNode(file: GltfFile, nodeIndex: Int, node: GltfNode, meshes: List<Mesh>): Node {
        val children = node.children.map { parseNode(file, it, file.nodes[it], meshes) }
        val mesh = node.mesh?.let { meshes[it] }

        val transform = TRSTransformation(
            translation = node.translation ?: Vector3d(),
            rotation = node.rotation ?: Quat4d(),
            scale = node.scale ?: Vector3d(1.0, 1.0, 1.0)
        )

        return Node(nodeIndex, children, transform, mesh, node.name)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseChannel(channel: GltfAnimationChannel, samplers: List<GltfAnimationSampler>,
                             accessors: List<Buffer>): Channel {

        val sampler = samplers[channel.sampler]
        val timeValues = accessors[sampler.input].data.map { (it as Number).toFloat() }

        return Channel(
            node = channel.target.node,
            path = GltfChannelPath.valueOf(channel.target.path),
            times = timeValues,
            interpolation = sampler.interpolation,
            values = accessors[sampler.output].data
        )
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun parseAnimations(file: GltfFile, accessors: List<Buffer>): List<Animation> {
        return file.animations.filter { it.channels != null }.map { animation ->
            val channels = animation.channels.map { parseChannel(it, animation.samplers, accessors) }
            Animation(animation.name, channels)
        }
    }

    class DefinitionTree(
        val scene: Int,
        val scenes: List<Scene>,
        val animations: List<Animation>,
        val textures: Set<ResourceLocation>,
        val extra: Any?
    )

    class Scene(
        val nodes: List<Node>
    )

    class Node(
        val index: Int,
        val children: List<Node>,
        val transform: TRSTransformation,
        val mesh: Mesh? = null,
        val name: String? = null
    )

    class Mesh(
        val primitives: List<Primitive>
    )

    class Primitive(
        val attributes: Map<GltfAttribute, Buffer>,
        val indices: Buffer? = null,
        val mode: GltfMode,
        val material: ResourceLocation
    )

    data class Buffer(
        val type: GltfType,
        val componentType: GltfComponentType,
        val data: List<Any>
    )

    class Animation(
        val name: String?,
        val channels: List<Channel>
    )

    class Channel(
        val node: Int,
        val path: GltfChannelPath,
        val times: List<Float>,
        val interpolation: GltfInterpolation,
        val values: List<Any>
    )
}
package io.typestream.kafka

import io.typestream.kafka.schemaregistry.SchemaRegistryClient
import io.typestream.kafka.schemaregistry.SchemaType
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class AvroSerde(private val schema: Schema) : Serde<GenericRecord>, Deserializer<GenericRecord>,
    Serializer<GenericRecord> {

    private var decoderFactory = DecoderFactory.get()
    private var encoderFactory = EncoderFactory.get()

    private lateinit var schemaRegistryClient: SchemaRegistryClient

    override fun serialize(topic: String, data: GenericRecord?): ByteArray? {
        if (data == null) {
            return null
        }

        val writer = GenericDatumWriter<GenericRecord>(schema)
        val out = ByteArrayOutputStream()

        out.write(0) // magic byte
        val id = schemaRegistryClient.register(topic, SchemaType.AVRO, schema.toString())
        out.write(ByteBuffer.allocate(4).putInt(id).array())

        val encoder = encoderFactory.binaryEncoder(out, null)
        writer.write(data, encoder)
        encoder.flush()

        val bytes = out.toByteArray()
        out.close()

        return bytes
    }

    override fun deserialize(topic: String?, data: ByteArray?): GenericRecord? {
        if (data == null) {
            return null
        }

        val buffer = ByteBuffer.wrap(data)

        buffer.get() // skip magic byte
        buffer.int // skip schema id

        val reader = GenericDatumReader<GenericRecord>(schema)

        val length = (buffer.limit() - 1) - 4 // take magic byte into account
        val start = buffer.position() + buffer.arrayOffset()

        return reader.read(null, decoderFactory.binaryDecoder(buffer.array(), start, length, null))
    }

    override fun serializer(): Serializer<GenericRecord> = this
    override fun deserializer(): Deserializer<GenericRecord> = this

    override fun close() {}
    override fun configure(configs: MutableMap<String, *>, isKey: Boolean) {
        configs["schema.registry.url"]?.let {
            schemaRegistryClient = SchemaRegistryClient(it.toString())
        }
    }
}
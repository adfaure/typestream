package io.typestream.filesystem

import io.typestream.compiler.ast.Cat
import io.typestream.compiler.ast.Expr
import io.typestream.compiler.ast.Grep
import io.typestream.compiler.ast.Pipeline
import io.typestream.compiler.types.Encoding
import io.typestream.config.SourcesConfig
import io.typestream.helpers.author
import io.typestream.testing.RedpandaContainerWrapper
import io.typestream.testing.avro.buildAuthor
import io.typestream.testing.konfig.testKonfig
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.stream.Stream
import kotlin.test.assertNull

@Testcontainers
internal class FileSystemTest {

    @Container
    private val testKafka = RedpandaContainerWrapper()

    private lateinit var fileSystem: FileSystem

    @BeforeEach
    fun beforeEach() {
        fileSystem = FileSystem(SourcesConfig(testKonfig(testKafka)), Dispatchers.IO)
    }

    @Test
    fun `expands paths correctly`() {
        fileSystem.use {
            assertThat(fileSystem.expandPath("dev", "/")).isEqualTo("/dev")
            assertThat(fileSystem.expandPath("dev/", "/")).isEqualTo("/dev")
            assertThat(fileSystem.expandPath("kafka", "/dev")).isEqualTo("/dev/kafka")
            assertThat(fileSystem.expandPath("/dev/kafka", "/")).isEqualTo("/dev/kafka")
            assertThat(fileSystem.expandPath("/dev/kafka", "/dev")).isEqualTo("/dev/kafka")
            assertThat(fileSystem.expandPath("", "/")).isEqualTo("/")
            assertThat(fileSystem.expandPath("..", "/dev")).isEqualTo("/")
            assertThat(fileSystem.expandPath("..", "/dev/kafka")).isEqualTo("/dev")
            assertNull(fileSystem.expandPath("dev/whatever", "/"))
        }
    }

    @Nested
    inner class EncodingRules {
        @Test
        fun `infers simple encoding`() {
            fileSystem.use {
                testKafka.produceRecords("authors", buildAuthor("Octavia E. Butler"))

                fileSystem.refresh()

                val dataCommand = Cat(listOf(Expr.BareWord("/dev/kafka/local/topics/authors")))

                dataCommand.dataStreams.add(author())

                assertThat(fileSystem.inferEncoding(dataCommand)).isEqualTo(Encoding.AVRO)
            }
        }

        @Test
        fun `infers pipeline encoding`() {
            fileSystem.use {
                testKafka.produceRecords("authors", buildAuthor("Emily St. John Mandel"))

                fileSystem.refresh()

                val cat = Cat(listOf(Expr.BareWord("/dev/kafka/local/topics/authors")))

                cat.dataStreams.add(author())

                val grep = Grep(listOf(Expr.BareWord("Mandel")))

                val pipeline = Pipeline(listOf(cat, grep))

                assertThat(fileSystem.inferEncoding(pipeline)).isEqualTo(Encoding.AVRO)
            }
        }
    }

    companion object {
        @JvmStatic
        fun incompletePaths(): Stream<Arguments> = Stream.of(
            Arguments.of("d", "/", listOf("dev/")),
            Arguments.of("/d", "/", listOf("/dev/")),
            Arguments.of("ka", "/dev", listOf("kafka/")),
            Arguments.of("kafka/lo", "/dev", listOf("kafka/local/")),
            Arguments.of("dev/kafka/lo", "/", listOf("dev/kafka/local/")),
            Arguments.of(
                "/dev/kafka/local/", "/", listOf(
                    "/dev/kafka/local/brokers/",
                    "/dev/kafka/local/consumer-groups/",
                    "/dev/kafka/local/topics/",
                    "/dev/kafka/local/schemas/"
                )
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("incompletePaths")
    fun `completes correctly`(incompletePath: String, pwd: String, suggestions: List<String>) {
        fileSystem.use {
            assertThat(fileSystem.completePath(incompletePath, pwd)).contains(*suggestions.toTypedArray())
        }
    }

    @Test
    fun `only completes directories with trailing slash`() {
        fileSystem.use {
            testKafka.produceRecords("authors", buildAuthor("Chimamanda Ngozi Adichie"))

            fileSystem.refresh()

            assertThat(
                fileSystem.completePath("dev/kafka/local/topics/a", "/")
            ).contains("dev/kafka/local/topics/authors")
        }
    }
}

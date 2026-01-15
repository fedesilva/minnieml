package mml.mmlclib.dev

import cats.effect.IO

import java.nio.file.{FileSystems, Path, StandardWatchEventKinds, WatchKey}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*

object FileWatcher:

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  def watchForChanges(filePath: Path): IO[Unit] =
    import cats.effect.Resource
    val watchServiceResource = Resource.make(
      IO.blocking(FileSystems.getDefault.newWatchService())
    )(ws => IO.blocking(ws.close()))

    watchServiceResource.use { watchService =>
      IO.blocking {
        filePath.toAbsolutePath.getParent.register(
          watchService,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_CREATE
        )
      } *> pollForChanges(watchService, filePath.getFileName)
    }

  private def pollForChanges(watchService: java.nio.file.WatchService, fileName: Path): IO[Unit] =
    IO.interruptible {
      val key: WatchKey = watchService.take()
      val events = key.pollEvents().asScala
      val detected = events.exists { event =>
        val kind         = event.kind()
        val eventPath    = event.context().asInstanceOf[Path]
        val isTargetFile = eventPath.toString == fileName.toString
        val isModifyOrCreate = kind.name() == StandardWatchEventKinds.ENTRY_MODIFY.name() ||
          kind.name() == StandardWatchEventKinds.ENTRY_CREATE.name()
        isTargetFile && isModifyOrCreate
      }
      key.reset()
      detected
    }.flatMap { detected =>
      if detected then IO.unit
      else pollForChanges(watchService, fileName)
    }

  def printChangeDetected(): IO[Unit] =
    IO {
      val timestamp = LocalDateTime.now().format(timestampFormatter)
      println(s"Changes detected: $timestamp")
    }

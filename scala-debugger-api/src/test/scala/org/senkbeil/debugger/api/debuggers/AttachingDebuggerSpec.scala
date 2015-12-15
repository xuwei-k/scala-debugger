package org.senkbeil.debugger.api.debuggers

import com.sun.jdi.connect.{AttachingConnector, Connector}
import com.sun.jdi.{VirtualMachine, VirtualMachineManager}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FunSpec, Matchers, OneInstancePerTest}
import org.senkbeil.debugger.api.profiles.ProfileManager
import org.senkbeil.debugger.api.utils.LoopingTaskRunner
import org.senkbeil.debugger.api.virtualmachines.{ScalaVirtualMachine, StandardScalaVirtualMachine}

import scala.collection.JavaConverters._

class AttachingDebuggerSpec extends FunSpec with Matchers
  with OneInstancePerTest with MockFactory
{
  private def createConnectorArgumentMock(
    setter: Boolean = false,
    getter: Option[String] = None
  ) = {
    val mockConnectorArgument = mock[Connector.Argument]
    if (getter.nonEmpty)
      (mockConnectorArgument.value _).expects().returning(getter.get).once()
    if (setter) (mockConnectorArgument.setValue _).expects(*).once()
    mockConnectorArgument
  }

  private val testPort = 1234
  private val testHostname = "some hostname"
  private val testTimeout = 9876

  private val mockVirtualMachineManager = mock[VirtualMachineManager]
  private val mockProfileManager = mock[ProfileManager]
  private val mockLoopingTaskRunner = mock[LoopingTaskRunner]
  private val mockNewScalaVirtualMachineFunc = mockFunction[
    VirtualMachine, ProfileManager, LoopingTaskRunner, StandardScalaVirtualMachine
  ]

  private class TestScalaVirtualMachine extends StandardScalaVirtualMachine(
    mockVirtualMachine, mockProfileManager, mockLoopingTaskRunner
  )
  private val mockScalaVirtualMachine = mock[TestScalaVirtualMachine]

  private class TestAttachingDebugger(
    override val isAvailable: Boolean = true,
    private val shouldJdiLoad: Boolean = true,
    private val customScalaVirtualMachine: Option[StandardScalaVirtualMachine] = Some(null)
  ) extends AttachingDebugger(
    virtualMachineManager = mockVirtualMachineManager,
    profileManager        = mockProfileManager,
    loopingTaskRunner     = mockLoopingTaskRunner,
    port                  = testPort,
    hostname              = testHostname,
    timeout               = testTimeout
  ) {
    override protected def newScalaVirtualMachine(
      virtualMachine: VirtualMachine,
      profileManager: ProfileManager,
      loopingTaskRunner: LoopingTaskRunner
    ): StandardScalaVirtualMachine = mockNewScalaVirtualMachineFunc(
      virtualMachine,
      profileManager,
      loopingTaskRunner
    )

    override def assertJdiLoaded(): Unit =
      if (!shouldJdiLoad) throw new AssertionError
  }

  private val mockVirtualMachine = mock[VirtualMachine]

  describe("AttachingDebugger") {
    describe("#start") {
      it("should throw an exception if unable to load JDI") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = false)

        intercept[AssertionError] {
          attachingDebugger.start((_) => {})
        }
      }

      it("should throw an exception if already started") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*).once()
        (mockLoopingTaskRunner.start _).expects().once()
        mockNewScalaVirtualMachineFunc.expects(*, *, *)
          .returning(stub[TestScalaVirtualMachine]).once()
        // MOCK ===============================================================

        attachingDebugger.start((_) => {})

        intercept[AssertionError] {
          attachingDebugger.start((_) => {})
        }
      }

      it("should throw an exception if unable to get the attaching connector") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)
        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning((Nil: Seq[AttachingConnector]).asJava)

        intercept[AssertionError] {
          attachingDebugger.start((_) => {})
        }
      }

      it("should invoke the callback function with the new virtual machine") {
        val expected = stub[TestScalaVirtualMachine]
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*)
          .returning(mockVirtualMachine).once()
        (mockLoopingTaskRunner.start _).expects().once()
        mockNewScalaVirtualMachineFunc.expects(*, *, *)
          .returning(expected).once()
        // MOCK ===============================================================

        var actual: ScalaVirtualMachine = null
        attachingDebugger.start(actual = _)

        actual should be (expected)
      }

      it("should initialize the new virtual machine") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*)
          .returning(mockVirtualMachine).once()
        (mockLoopingTaskRunner.start _).expects().once()
        // MOCK ===============================================================

        mockNewScalaVirtualMachineFunc.expects(mockVirtualMachine, *, *)
          .returning(mockScalaVirtualMachine).once()

        (mockScalaVirtualMachine.initialize _).expects(true).once()

        attachingDebugger.start(_ => {})
      }
    }

    describe("#isRunning") {
      it("should return true if already started") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*)
          .returning(mockVirtualMachine).once()
        (mockLoopingTaskRunner.start _).expects().once()
        mockNewScalaVirtualMachineFunc.expects(*, *, *)
          .returning(stub[TestScalaVirtualMachine]).once()
        // MOCK ===============================================================

        attachingDebugger.start(_ => {})

        attachingDebugger.isRunning should be (true)
      }

      it("should return false if not started (or started and then stopped)") {
        val attachingDebugger = new TestAttachingDebugger()
        attachingDebugger.isRunning should be (false)
      }
    }

    describe("#stop") {
      it("should throw an exception if not started") {
        val attachingDebugger = new TestAttachingDebugger()

        intercept[AssertionError] {
          attachingDebugger.stop()
        }
      }

      it("should dispose of the virtual machine mirror") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*)
          .returning(mockVirtualMachine).once()
        (mockLoopingTaskRunner.start _).expects().once()
        mockNewScalaVirtualMachineFunc.expects(*, *, *)
          .returning(stub[TestScalaVirtualMachine]).once()
        // MOCK ===============================================================

        attachingDebugger.start((_) => {})

        (mockLoopingTaskRunner.stop _).expects(true).once()
        (mockVirtualMachine.dispose _).expects().once()

        attachingDebugger.stop()
      }
    }

    describe("#process") {
      it("should return Some process if the JVM has been attached") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        // MOCK ===============================================================
        val mockAttachingConnector = mock[AttachingConnector]

        (mockAttachingConnector.name _).expects()
          .returning("com.sun.jdi.SocketAttach")

        (mockVirtualMachineManager.attachingConnectors _).expects()
          .returning(Seq(mockAttachingConnector).asJava)

        (mockAttachingConnector.defaultArguments _).expects().returning(Map(
          "hostname" -> createConnectorArgumentMock(setter = true),
          "port" -> createConnectorArgumentMock(setter = true),
          "timeout" -> createConnectorArgumentMock(setter = true)
        ).asJava)

        (mockAttachingConnector.attach _).expects(*)
          .returning(mockVirtualMachine).once()
        (mockLoopingTaskRunner.start _).expects().once()
        mockNewScalaVirtualMachineFunc.expects(*, *, *)
          .returning(stub[TestScalaVirtualMachine]).once()
        // MOCK ===============================================================

        val mockProcess = mock[Process]
        (mockVirtualMachine.process _).expects().returning(mockProcess).once()

        attachingDebugger.start((_) => {})

        attachingDebugger.process should be (Some(mockProcess))
      }

      it("should return None if the JVM has not been attached") {
        val attachingDebugger = new TestAttachingDebugger(shouldJdiLoad = true)

        attachingDebugger.process should be (None)
      }
    }
  }
}

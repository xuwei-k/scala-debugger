package org.scaladebugger.api.lowlevel.monitors

import java.util.concurrent.atomic.AtomicBoolean

import com.sun.jdi.event.MonitorContendedEnteredEvent
import org.scaladebugger.api.lowlevel.events.EventType._
import org.scaladebugger.api.virtualmachines.DummyScalaVirtualMachine
import org.scaladebugger.test.helpers.ParallelMockFunSpec
import test.{ApiTestUtilities, VirtualMachineFixtures}

class StandardMonitorContendedEnteredManagerIntegrationSpec extends ParallelMockFunSpec
  with VirtualMachineFixtures
  with ApiTestUtilities
{
  describe("StandardMonitorContendedEnteredManager") {
    it("should trigger when a thread enters a monitor after waiting for it to be released by another thread") {
      val testClass = "org.scaladebugger.test.monitors.MonitorContendedEntered"

      val detectedEntered = new AtomicBoolean(false)

      val s = DummyScalaVirtualMachine.newInstance()
      import s.lowlevel._

      // Mark that we want to receive monitor contended entered events and
      // watch for one
      monitorContendedEnteredManager.createMonitorContendedEnteredRequest()
      eventManager.addResumingEventHandler(MonitorContendedEnteredEventType, e => {
        val monitorContendedEnteredEvent =
          e.asInstanceOf[MonitorContendedEnteredEvent]

        val threadName = monitorContendedEnteredEvent.thread().name()
        val monitorTypeName =
          monitorContendedEnteredEvent.monitor().referenceType().name()

        logger.debug(s"Detected monitor entered in thread $threadName for monitor of type $monitorTypeName")
        detectedEntered.set(true)
      })

      withVirtualMachine(testClass, pendingScalaVirtualMachines = Seq(s)) { (s) =>
        // Eventually, we should receive the monitor contended entered event
        logTimeTaken(eventually {
          // NOTE: Using asserts to provide more helpful failure messages
          assert(detectedEntered.get(), s"No monitor entered was detected!")
        })
      }
    }
  }
}

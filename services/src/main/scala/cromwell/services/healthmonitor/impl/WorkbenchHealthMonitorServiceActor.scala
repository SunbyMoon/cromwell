package cromwell.services.healthmonitor.impl

import cromwell.services.healthmonitor.HealthMonitorServiceActor
import cromwell.services.healthmonitor.HealthMonitorServiceActor.Subsystem

/*
  Checks:

  PAPI (if backend exists)
  GCS (if filesystem exists)
  DB
  Dockerhub (if exists)
 */

class WorkbenchHealthMonitorServiceActor extends HealthMonitorServiceActor {
  override val subsystems = List.empty[Subsystem]
}

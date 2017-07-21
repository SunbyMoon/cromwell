package cromwell.services.healthmonitor.impl

import cromwell.services.healthmonitor.HealthMonitorServiceActor
import cromwell.services.healthmonitor.HealthMonitorServiceActor.Subsystem

/*
  Checks:
  DB
  Dockerhub (if exists)
*/

class StandardHealthMonitorServiceActor extends HealthMonitorServiceActor {
  override val subsystems = List.empty[Subsystem]
}

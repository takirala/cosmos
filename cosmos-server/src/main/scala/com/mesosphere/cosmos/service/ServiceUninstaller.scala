package com.mesosphere.cosmos.service

import com.mesosphere.cosmos.AdminRouter
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.twitter.finagle.http.Status
import com.twitter.util.Try.PredicateDoesNotObtain
import com.twitter.util.Duration
import com.twitter.util.Future
import com.twitter.util.Timer

final class ServiceUninstaller(
  adminRouter: AdminRouter
)(
  implicit timer: Timer
) {
  private[this] val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(getClass)

  def uninstall(
    appId: AppId,
    deploymentId: String,
    retries: Int = ServiceUninstaller.DefaultRetries
  )(
    implicit session: RequestSession
  ): Future[Unit] = {
    val work = for {
      deployed <- checkDeploymentCompleted(deploymentId) if deployed
      marathonResponse <- adminRouter.getApp(appId)
      uninstalled <- checkSdkUninstallCompleted(
        marathonResponse.app.id,
        marathonResponse.app.labels.getOrElse(ServiceUninstaller.CommonsVersionLabel, "v1")
      ) if uninstalled
      deleted <- delete(appId) if deleted
    } yield ()

    work.rescue {
      case ex if retries > 0  =>
        if (!ex.isInstanceOf[PredicateDoesNotObtain]) {
          logger.info(s"Uninstall attempt for $appId didn't finish. $retries retries left.", ex)
        }

        Future.sleep(
          Duration.fromSeconds(10) // scalastyle:ignore magic.number
        ).before(
          uninstall(appId, deploymentId, retries - 1)
        )
    }
  }

  private def checkDeploymentCompleted(
    deploymentId: String
  )(
    implicit session: RequestSession
  ): Future[Boolean] = {
    for {
      deployments <- adminRouter.listDeployments()
    } yield {
      val completed = !deployments.exists(_.id == deploymentId)
      val status = if (completed) "complete" else "still in progress"
      logger.info(s"Deployment $status. Id: $deploymentId")
      completed
    }
  }

  private def checkSdkUninstallCompleted(
    appId: AppId,
    apiVersion: String
  )(
    implicit session: RequestSession
  ): Future[Boolean] = {
    adminRouter.getSdkServicePlanStatus(
      appId,
      apiVersion,
      "deploy"
    ).map(_.status == Status.Ok)
  }

  private def delete(
    appId: AppId
  )(
    implicit session: RequestSession
  ): Future[Boolean] = {
    adminRouter.deleteApp(appId).map { response =>
      if (response.status.code >= 400 && response.status.code < 500) {
        logger.error(
          s"Encountered Marathon error : ${response.status} when deleting $appId. Giving up."
        )
        true
      } else if (response.status == Status.Ok) {
        logger.info(s"Deleted app: $appId")
        true
      } else {
        false
      }
    }
  }
}

object ServiceUninstaller {
  def apply(
    adminRouter: AdminRouter
  )(
    implicit timer: Timer
  ): ServiceUninstaller = {
    new ServiceUninstaller(adminRouter)
  }

  private val CommonsVersionLabel: String = "DCOS_COMMONS_API_VERSION"
  private val DefaultRetries: Int = 50
}

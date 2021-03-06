package com.mesosphere.cosmos.handler

import com.mesosphere.cosmos.AdminRouter
import com.mesosphere.cosmos.finch.EndpointHandler
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.render.PackageDefinitionRenderer
import com.mesosphere.cosmos.repository.PackageCollection
import com.mesosphere.cosmos.rpc
import com.mesosphere.cosmos.thirdparty.marathon.model.MarathonApp
import com.mesosphere.universe
import com.mesosphere.universe.v3.syntax.PackageDefinitionOps._
import com.mesosphere.universe.v4.model.PackageDefinition
import com.twitter.util.Future
import io.circe.JsonObject

private[cosmos] final class ServiceDescribeHandler(
  adminRouter: AdminRouter,
  packageCollection: PackageCollection
) extends EndpointHandler[rpc.v1.model.ServiceDescribeRequest, rpc.v1.model.ServiceDescribeResponse] {
  override def apply(
    request: rpc.v1.model.ServiceDescribeRequest)(implicit
    session: RequestSession
  ): Future[rpc.v1.model.ServiceDescribeResponse] = {
    for {
      marathonAppResponse <- adminRouter.getApp(request.appId)
      packageDefinition <- getPackageDefinition(marathonAppResponse.app)
      upgradesTo <- packageCollection.upgradesTo(packageDefinition.name, packageDefinition.version)
      downgradesTo <- packageCollection.downgradesTo(packageDefinition)
    } yield {
      val userProvidedOptions = marathonAppResponse.app.serviceOptions
      rpc.v1.model.ServiceDescribeResponse(
        `package` = packageDefinition,
        upgradesTo = upgradesTo,
        downgradesTo = downgradesTo,
        resolvedOptions = getResolvedOptions(packageDefinition, userProvidedOptions),
        userProvidedOptions = userProvidedOptions
      )
    }
  }

  private def getPackageDefinition(
    app: MarathonApp)(implicit
    session: RequestSession
  ): Future[universe.v4.model.PackageDefinition] = {
    app.packageDefinition.map(Future.value).getOrElse {
      val (name, version) =
        app.packageName.flatMap(name => app.packageVersion.map(name -> _))
          .getOrElse(throw new IllegalStateException(
            "The name and version of the service were not found in the labels"))
      packageCollection
        .getPackageByPackageVersion(name, Some(version))
        .map(_._1)
    }
  }

  private def getResolvedOptions(
    packageDefinition: PackageDefinition,
    serviceOptions: Option[JsonObject]
  ): Option[JsonObject] = {
    serviceOptions.map { userSuppliedOptions =>
      PackageDefinitionRenderer.mergeDefaultAndUserOptions(packageDefinition, Some(userSuppliedOptions))
    }
  }
}

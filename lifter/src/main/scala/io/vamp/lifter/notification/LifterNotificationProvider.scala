package io.vamp.lifter.notification

import io.vamp.common.notification.{ DefaultPackageMessageResolverProvider, LoggingNotificationProvider }

trait LifterNotificationProvider extends LoggingNotificationProvider with DefaultPackageMessageResolverProvider
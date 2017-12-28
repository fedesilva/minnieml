package com.cosomojo.pl.test

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSuite, Matchers, MustMatchers, PropSpec}

trait BaseFunSuite
  extends FunSuite
    with Matchers


trait BasePropSuite
  extends PropSpec
    with TableDrivenPropertyChecks
    with Matchers

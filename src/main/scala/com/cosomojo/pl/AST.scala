package com.cosomojo.pl

sealed trait Expr

case class Lit(value: Int) extends Expr

case class Let(name: String, expr: Expr) extends Expr

case class Abs(name: String, params: Seq[String], body: Expr) extends Expr

case class App(abs: String, params: Seq[Expr]) extends Expr
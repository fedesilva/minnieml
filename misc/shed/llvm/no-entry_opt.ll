; ModuleID = 'out/no-entry.bc'
source_filename = "no-entry.ll"
target datalayout = "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-unknown-unknown"

@x = local_unnamed_addr global i32 3
@y = local_unnamed_addr global i32 4
@a = local_unnamed_addr global i32 2
@llvm.global_ctors = appending global [0 x { i32, ptr, ptr }] zeroinitializer

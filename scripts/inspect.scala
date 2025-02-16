import mml.mmlclib.api.*
import mml.mmlclib.util.yolo.*
import cats.syntax.all.*

val src_roto =
  """module A =
    |  let roto =
    |  let a = 1;
    |  let b = "2";
    |  let c = true;
    |  let d = x;
    |;
  """.stripMargin

val src2 =
  """module A =
    | let xs = 1 sum 2;
    |;
    |""".stripMargin

val src3 =
  """
    |module A =
    | fn sum a b = a + b;
    | let xs = 1 + 2;
    | fn main() = sum 1 xs;
    |;
    |""".stripMargin

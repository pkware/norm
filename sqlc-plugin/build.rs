use protobuf_codegen::Codegen;

fn main() {
  Codegen::new()
    .pure()
    .cargo_out_dir("proto")
    .input("../proto/codegen.proto")
    .include("../proto")
    .run_from_script();
}

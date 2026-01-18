use protobuf::Message;
use protobuf_json_mapping::print_to_string;
use std::io;
use std::io::prelude::*;

include!(concat!(env!("OUT_DIR"), "/proto/mod.rs"));

pub fn create_codegen_response(content: &String) -> codegen::GenerateResponse {
    let mut file = codegen::File::default();
    file.name = "schema.json".to_string();
    file.contents = content.as_bytes().to_vec();

    let mut resp = codegen::GenerateResponse::new();
    resp.files.push(file);
    resp
}

fn main() {
    let mut buffer = Vec::new();
    io::stdin().read_to_end(&mut buffer).unwrap();

    let request = codegen::GenerateRequest::parse_from_bytes(&buffer).unwrap();
    let json = print_to_string(&request).unwrap();

    let _plugin_options_json = String::from_utf8(request.plugin_options).unwrap();
    let response = create_codegen_response(&json);
    let response_bytes = response.write_to_bytes().unwrap();

    io::stdout().write_all(&response_bytes).unwrap();
}

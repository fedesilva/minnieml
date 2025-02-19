#!/bin/bash

# Print instructions at the beginning of the script
echo "this is my codebase, I will ask questions and request fixes."
echo " always provide full files for me to copy and paste if I ask for fixes"
echo " also provide after full file, if there is only one change, the specific change"
echo " always use scala 3 new syntax"
echo " if providing a fix and file is not new syntax, rewrite it to be"
echo " stand by for further queries"
echo

# Function to process Scala files
process_scala_files() {
  local dir="$1"
  local base_dir="$1"

  # Check if the directory exists
  if [ -d "$dir" ]; then
    # Find all .scala files in the current directory and its subdirectories
    find "$dir" -type f -name "*.scala" | while read -r file; do
      # Get the relative path from the base directory
      relative_path="${file#$base_dir/}"

      # Echo the file path and contents
      echo "// start $relative_path"
      cat "$file"
      echo "// end $relative_path"
    done
  else
    echo "Directory $dir not found, skipping..."
  fi
}

# Function to process resource files like .conf, .html, .js
process_resource_files() {
  local dir="$1"
  local base_dir="$1"

  # Check if the directory exists
  if [ -d "$dir" ]; then
    # Find all .conf, .html, and .js files excluding certain generated directories (e.g., dist, out, target)
    find "$dir" -type f \( -name "*.conf" -o -name "*.html" -o -name "*.js" -o -name "*.g4" -o -name "*.sbt" -o -name "*.md" \) \
      -not -path "*/dist/*" -not -path "*/out/*" -not -path "*/target/*" | while read -r file; do
      # Get the relative path from the base directory
      relative_path="${file#$base_dir/}"

      # Echo the file path and contents
      echo "// start $relative_path"
      cat "$file"
      echo "// end $relative_path"
    done
  else
    echo "Directory $dir not found, skipping..."
  fi
}

# compiler
process_scala_files "modules/mmlc/src"

# lib
process_scala_files "modules/mmlc-lib/src"



# Process sbt, conf, html, and js files in resources and project directories, ignoring generated directories
process_resource_files "modules/mmlc-lib/src/main/antl4"

# Process sbt and conf files at the project root level
process_resource_files "."

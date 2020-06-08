/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package jsonpath

import (
	"encoding/json"
	"log"
	"os"
	"path"
	"path/filepath"
	"testing"
	"github.com/stretchr/testify/assert"
	"github.com/op/go-logging"
	"golang.linkedin.com/iddecorator/iddecorator/utils"
)

func setupJsonPath(name string) *JSONPath {
	j := New(name)
	log := logging.MustGetLogger(name)
	logging.SetLevel(logging.DEBUG, name)
	j.logger = log
	j.AllowMissingKeys(true)
	return j
}
var raw = []byte(`{
	"spec": {
		"tfReplicaSpecs": {
			"Ps": {
				"template": {
					"metadata": {
						"annotations": {
							"haha": "true"
						}
					}
				}
			},
			"Worker": {
				"template": {
					"metadata": {
						"annotations": {
							"hoho": "true"
						}
					}
				}
			},
			"Chief": {
				"template": {
					"metadata": {
						"annotations": {
							"raah": "true"
						}
					}
				}
			},
			"Evaluator": {
				"template": {
					"metadata": {
					}
				}
			}
		}
	}
}`)


func TestFindPaths_tfjob(t *testing.T) {
	j := setupJsonPath("test")
	dir, err := os.Getwd()
	if err != nil {
		log.Fatal(err)
	}
	yamlFile := path.Join(filepath.Dir(dir), "testdata", "tfjobs.yaml")
	input := utils.ParseYaml(yamlFile)
	template := `{..metadata.annotations}`
	if err := j.Parse(template); err == nil {
		if _, err := j.FindResults(input); err != nil {
			t.Errorf("error %v", err)
		}
	} else {
		t.Errorf("error %v", err)
	}
	paths := []string{"/spec/tfReplicaSpecs/Worker/template/metadata/annotations",
		"/spec/tfReplicaSpecs/Chief/template/metadata/annotations",
		"/spec/tfReplicaSpecs/Evaluator/template/metadata/annotations",
		"/spec/tfReplicaSpecs/Ps/template/metadata/annotations",
		"/metadata/annotations",
		}
	assert.ElementsMatch(t, paths, j.FindPaths())
}

func TestFindPaths_mpijob(t *testing.T) {
	j := setupJsonPath("test")
	dir, err := os.Getwd()
	if err != nil {
		log.Fatal(err)
	}
	yamlFile := path.Join(filepath.Dir(dir), "testdata", "mpijob.yaml")
	input := utils.ParseYaml(yamlFile)
	template := `{..metadata.annotations}`
	if err := j.Parse(template); err == nil {
		if _, err := j.FindResults(input); err != nil {
			t.Errorf("error %v", err)
		}
	} else {
		t.Errorf("error %v", err)
	}
	paths := []string{"/spec/mpiReplicaSpecs/Launcher/template/metadata/annotations",
		"/spec/mpiReplicaSpecs/Worker/template/metadata/annotations",
		"/metadata/annotations",
	}
	assert.ElementsMatch(t, paths, j.FindPaths())
}

func TestFindPaths(t *testing.T) {
	var input interface{}
	json.Unmarshal(raw, &input)
	name := "test"
	template := `{..metadata.annotations}`
	j := setupJsonPath(name)
	if err := j.Parse(template); err == nil {
		if _, err := j.FindResults(input); err != nil {
			t.Errorf("error %v", err)
		}
	}
	paths := []string{"/spec/tfReplicaSpecs/Worker/template/metadata/annotations",
		"/spec/tfReplicaSpecs/Chief/template/metadata/annotations",
		"/spec/tfReplicaSpecs/Ps/template/metadata/annotations",
	}
	assert.ElementsMatch(t, paths, j.FindPaths())
}

func TestFindExactPaths(t *testing.T) {
	var input interface{}
	json.Unmarshal(raw, &input)
	name := "test"
	template := `{..spec.tfReplicaSpecs.Chief.template.metadata.annotations}`
	j := setupJsonPath(name)
	if err := j.Parse(template); err == nil {
		if _, err := j.FindResults(input); err != nil {
			t.Errorf("error %v", err)
		}
	}
	paths := []string{
		"/spec/tfReplicaSpecs/Chief/template/metadata/annotations",
	}
	assert.ElementsMatch(t, paths, j.FindPaths())
}
// macondo-reference exchanges one JSON line per turn with the local referee.
package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/domino14/macondo/board"
	"github.com/domino14/macondo/cross_set"
	"github.com/domino14/macondo/move"
	"github.com/domino14/macondo/movegen"
	"github.com/domino14/word-golib/kwg"
	"github.com/domino14/word-golib/tilemapping"
	"github.com/rs/zerolog"
)

const french = `?,2,0,0
A,9,1,1
B,2,3,0
C,2,3,0
D,3,2,0
E,15,1,1
F,2,4,0
G,2,2,0
H,2,4,0
I,8,1,1
J,1,8,0
K,1,10,0
L,5,1,0
M,3,2,0
N,6,1,0
O,6,1,1
P,2,3,0
Q,1,8,0
R,6,1,0
S,6,1,0
T,6,1,0
U,6,1,1
V,2,4,0
W,1,10,0
X,1,10,0
Y,1,10,1
Z,1,10,0`

type request struct {
	Board    string `json:"board"`
	Rack     string `json:"rack"`
	Exchange bool   `json:"exchange_allowed"`
}

type decision struct {
	Type     string `json:"type"`
	Row      int    `json:"row"`
	Col      int    `json:"col"`
	Vertical bool   `json:"vertical"`
	Tiles    string `json:"tiles"`
	Score    int    `json:"score"`
}

func answer(input request, graph *kwg.KWG, distribution *tilemapping.LetterDistribution) (decision, error) {
	const side = 15
	if len(input.Board) != side*side || len(input.Rack) == 0 || len(input.Rack) > 7 {
		return decision{}, errors.New("invalid board or rack dimensions")
	}
	alphabet := distribution.TileMapping()
	gameBoard := board.MakeBoard(board.CrosswordGameBoard)
	for row := 0; row < side; row++ {
		var letters [side]tilemapping.MachineLetter
		for col, letter := range []byte(input.Board[row*side : (row+1)*side]) {
			switch {
			case letter == '.':
			case letter >= 'A' && letter <= 'Z':
				letters[col] = tilemapping.MachineLetter(letter - 'A' + 1)
			case letter >= 'a' && letter <= 'z':
				letters[col] = tilemapping.MachineLetter(letter-'a'+1) | 0x80
			default:
				return decision{}, errors.New("invalid board letter")
			}
		}
		gameBoard.SetRowMLs(row, letters[:])
	}
	for _, letter := range input.Rack {
		if letter != '?' && (letter < 'A' || letter > 'Z') {
			return decision{}, errors.New("invalid rack letter")
		}
	}
	gameBoard.UpdateAllAnchors()
	cross_set.GenAllCrossSets(gameBoard, graph, distribution)
	generator := movegen.NewGordonGenerator(graph, gameBoard, distribution)
	rack := tilemapping.RackFromString(input.Rack, alphabet)
	var best *move.Move
	for _, candidate := range generator.GenAll(rack, false) {
		if candidate.Action() == move.MoveTypePlay && (best == nil || candidate.TiebreaksBetter(best)) {
			best = candidate
		}
	}
	if best != nil {
		row, col, vertical := best.CoordsAndVertical()
		var tiles strings.Builder
		for _, tile := range best.Tiles() {
			if tile == 0 {
				tiles.WriteByte('.')
			} else {
				tiles.WriteString(tile.UserVisible(alphabet, true))
			}
		}
		return decision{"play", row, col, vertical, tiles.String(), best.Score()}, nil
	}
	// This reference maximizes immediate points. If no move exists it changes its
	// ordinary tiles when legal, retaining blanks and S for the next turn.
	if input.Exchange {
		tiles := strings.Map(func(letter rune) rune {
			if letter == '?' || letter == 'S' {
				return -1
			}
			return letter
		}, input.Rack)
		if tiles != "" {
			return decision{Type: "exchange", Tiles: tiles}, nil
		}
	}
	return decision{Type: "pass"}, nil
}

func run(input io.Reader, output io.Writer) error {
	var graphBytes uint32
	if err := binary.Read(input, binary.LittleEndian, &graphBytes); err != nil {
		return fmt.Errorf("graph length: %w", err)
	}
	if graphBytes < 12 || graphBytes > 64*1024*1024 || graphBytes%4 != 0 {
		return errors.New("invalid graph length")
	}
	payload := make([]byte, graphBytes)
	if _, err := io.ReadFull(input, payload); err != nil {
		return fmt.Errorf("graph data: %w", err)
	}
	graph, err := kwg.ScanKWG(bytes.NewReader(payload), len(payload))
	if err != nil {
		return err
	}
	distribution, err := tilemapping.ScanLetterDistribution(strings.NewReader(french))
	if err != nil {
		return err
	}
	writer := json.NewEncoder(output)
	if err := writer.Encode(map[string]string{
		"engine": "Macondo v0.13.9", "policy": "max_score", "protocol": "wordrivet.macondo.v1",
	}); err != nil {
		return err
	}
	lines := bufio.NewScanner(input)
	lines.Buffer(make([]byte, 1024), 4096)
	for lines.Scan() {
		var query request
		if err := json.Unmarshal(lines.Bytes(), &query); err != nil {
			return err
		}
		move, err := answer(query, graph, distribution)
		if err != nil {
			return err
		}
		if err := writer.Encode(move); err != nil {
			return err
		}
	}
	return lines.Err()
}

func main() {
	zerolog.SetGlobalLevel(zerolog.WarnLevel)
	if err := run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

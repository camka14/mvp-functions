package dataTypes.enums

enum class Side {
    LEFT,
    RIGHT;

    fun opposite(): Side {
        return if (this == LEFT) {
            RIGHT
        } else {
            LEFT
        }
    }
}
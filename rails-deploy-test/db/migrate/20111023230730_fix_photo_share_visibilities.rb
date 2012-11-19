class FixPhotoShareVisibilities < ActiveRecord::Migration
  class Photo < ActiveRecord::Base; end

  def self.up
    return  if ! Photo.first.respond_to?(:tmp_old_id)

      ['aspect_visibilities', 'share_visibilities'].each do |vis_table|
        ActiveRecord::Base.connection.execute <<SQL
        UPDATE #{vis_table}
          SET shareable_type='Post'
SQL
        ActiveRecord::Base.connection.execute <<SQL
        UPDATE #{vis_table}, photos
          SET #{vis_table}.shareable_type='Photo', #{vis_table}.shareable_id=photos.id
            WHERE #{vis_table}.shareable_id=photos.tmp_old_id
SQL
      end
  end

  def self.down
  end
end
